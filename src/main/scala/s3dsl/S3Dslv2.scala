package s3dsl

import java.io.{BufferedInputStream, InputStream}
import java.time.ZonedDateTime

import cats.effect.{ConcurrentEffect, ContextShift, Sync}
import cats.implicits._
import mouse.boolean._
import mouse.option._
import eu.timepit.refined.cats.syntax._
import fs2.{Pipe, Stream}
import s3dsl.domain.S3._
import s3dsl.domain.auth.Domain.{PolicyRead, PolicyWrite}
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{StorageClass => _, _}

import collection.JavaConverters._
import scala.concurrent.ExecutionContext

trait S3Dslv2[F[_]] {

  def createBucket(bucket: BucketName): F[Unit]
  def deleteBucket(bucket: BucketName): F[Unit]
  def doesBucketExist(bucket: BucketName): F[Boolean]
  def listBuckets: F[List[BucketName]]

  def getBucketAcl(bucket: BucketName): F[Option[AccessControlList]]
  def getBucketPolicy(bucket: BucketName): F[Option[PolicyRead]]
  def setBucketPolicy(bucket: BucketName, policy: PolicyWrite): F[Unit]

  def getObject(path: Path, chunkSize: Int): F[Option[Object[F]]]
  def getObjectMetadata(path: Path): F[Option[ObjectMetadata]]
  def doesObjectExist(path: Path): F[Boolean]
  def putObject(path: Path, contentLength: Long, contentType: Option[String], userMetadata: Map[String, String]): Pipe[F, Byte, Unit]
  def copyObject(src: Path, dest: Path): F[Unit]
  def listObjects(path: Path): Stream[F, ObjectSummary]
  def deleteObject(path: Path): F[Unit]
  def generatePresignedUrl(path: Path, expiration: ZonedDateTime, method: HTTPMethod): F[URL]
}

object S3Dslv2 {


  def interpreter[F[_]: ConcurrentEffect](s3: S3Client,
                                          cs: ContextShift[F],
                                          blockingEc: ExecutionContext): S3Dslv2[F] = {

    val F = ConcurrentEffect[F]

    implicit class SyncSyntax(val sync: Sync[F]) {
      def blocking[A](fa: => A): F[A] = cs.evalOn(blockingEc)(sync.delay(fa))
    }

    new S3Dslv2[F] {
      override def createBucket(bucket: BucketName): F[Unit] = F.blocking(
        s3.createBucket(CreateBucketRequest.builder.bucket(bucket.value).build)
      ).void

      override def deleteBucket(bucket: BucketName): F[Unit] = F.blocking(
        s3.deleteBucket(DeleteBucketRequest.builder.bucket(bucket.value).build)
      ).void

      override def doesBucketExist(bucket: BucketName): F[Boolean] = F.blocking(
        s3.headBucket(HeadBucketRequest.builder.bucket(bucket.value).build)
      ).exists

      override def listBuckets: F[List[BucketName]] = F.blocking(s3.listBuckets)
        .map(_.buckets.asScala.toList.map(b => bucketNameOrErr(b.name)))


      override def getBucketAcl(bucket: BucketName): F[Option[AccessControlList]] = ???

      override def getBucketPolicy(bucket: BucketName): F[Option[PolicyRead]] = ???

      override def setBucketPolicy(bucket: BucketName, policy: PolicyWrite): F[Unit] = ???


      override def getObject(path: Path, chunkSize: Int): F[Option[Object[F]]] = {

        def convert(aws: ResponseInputStream[GetObjectResponse]): F[Object[F]] = {
          def toMeta(aws: GetObjectResponse): ObjectMetadata = {
            val contentType = Option(aws.contentType).map(ContentType.apply)
            val etag = Option(aws.eTag).map(ETag.apply)
            val expiration = Option(aws.expires).map(ExpirationTime.apply)
            val storClass = Option(aws.storageClass).map(sc => StorageClass(sc.toString))
            val lastModified = Option(aws.lastModified).map(LastModified.apply)
            ObjectMetadata(contentType, aws.contentLength, None, etag, expiration, storClass, lastModified)
          }

          F.delay(
            Object[F](
              stream = fs2.io.readInputStream[F](F.pure(aws), chunkSize, blockingEc, closeAfterUse = true)(F, cs),
              meta = toMeta(aws.response)
            )
          )
        }

        val read = F.blocking(
          s3.getObject(GetObjectRequest.builder.bucket(path.bucket.value).key(path.key.value).build)
        ).handle404

        read.flatMap(_.traverse(convert))
      }

      override def getObjectMetadata(path: Path): F[Option[ObjectMetadata]] = {
        def toMeta(aws: HeadObjectResponse): ObjectMetadata = {
          val contentType = Option(aws.contentType).map(ContentType.apply)
          val etag = Option(aws.eTag).map(ETag.apply)
          val expiration = Option(aws.expires).map(ExpirationTime.apply)
          val storClass = Option(aws.storageClass).map(sc => StorageClass(sc.toString))
          val lastModified = Option(aws.lastModified).map(LastModified.apply)
          ObjectMetadata(contentType, aws.contentLength, None, etag, expiration, storClass, lastModified)
        }
        F.blocking(
          s3.headObject(HeadObjectRequest.builder.bucket(path.bucket.value).key(path.key.value).build)
        ).map(toMeta).handle404
      }

      override def doesObjectExist(path: Path): F[Boolean] = F.blocking(
        s3.headObject(HeadObjectRequest.builder.bucket(path.bucket.value).key(path.key.value).build)
      ).exists

      override def putObject(path: Path,
                             contentLength: Long,
                             contentType: Option[String],
                             userMetadata: Map[String, String]): Pipe[F, Byte, Unit] = fs2In =>
        fs2.io.toInputStream(F)(fs2In).through(FS2.liftPipe(putObj(path, contentLength, contentType, userMetadata)))

      override def copyObject(src: Path, dest: Path): F[Unit] = F.blocking(
        s3.copyObject(
          CopyObjectRequest.builder.copySource(src.toString).bucket(dest.bucket.value).key(dest.key.value).build
        )
      ).void

      override def listObjects(path: Path): Stream[F, ObjectSummary] = {

        def toSummary(aws: S3Object): ObjectSummary = {
          val p = Path(path.bucket, keyOrErr(aws.key))
          val etag = Option(aws.eTag).map(ETag.apply)
          val storClass = Option(aws.storageClass).map(sc => StorageClass(sc.toString))
          val lastModified = Option(aws.lastModified).map(LastModified.apply)
          ObjectSummary(p, aws.size, etag, storClass, lastModified)
        }

        val responses = F.blocking(
          s3.listObjectsV2Paginator(
            ListObjectsV2Request.builder.bucket(path.bucket.value).prefix(path.key.value).maxKeys(1000).build
          )
        )

       Stream.eval(responses.map(_.iterator))
          .flatMap(i =>
            Stream.repeatEval(F.blocking(
              i.hasNext.fold(i.next.contents.asScala.toList.toNel, None)
            )).unNoneTerminate
          )
         .flatMap(nel => Stream.emits(nel.toList).covary[F])
         .map(toSummary)
      }

      override def deleteObject(path: Path): F[Unit] = F.blocking (
        s3.deleteObject(DeleteObjectRequest.builder.bucket(path.bucket.value).key(path.key.value).build)
      ).void

      override def generatePresignedUrl(path: Path, expiration: ZonedDateTime, method: HTTPMethod): F[URL] = ???

      private def putObj(path: Path, contentLength: Long, contentType: Option[String], userMetadata: Map[String, String])
                        (is: InputStream): F[Unit] = {

        def reqBuilder(contentType: Option[String]): PutObjectRequest.Builder = {
          val b = PutObjectRequest.builder
          contentType.cata(b.contentType, b)
        }

        val req = reqBuilder(contentType).bucket(path.bucket.value).key(path.key.value)
          .contentLength(contentLength)
          .metadata(userMetadata.asJava)
          .build

        val bufferedIs = new BufferedInputStream(is)
        F.blocking(s3.putObject(req, RequestBody.fromInputStream(bufferedIs, contentLength))).void
      }
    }

  }

  private def bucketNameOrErr(s: String): BucketName = BucketName.validate(s).fold(
    l => sys.error(s"Programming error in bucket name validation: $l"), identity
  )

  private def keyOrErr(s: String): Key = Key.validate(s).fold(
    l => sys.error(s"Programming error in s3 key validation: $l"), identity
  )

}
