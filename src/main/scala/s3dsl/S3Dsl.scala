package s3dsl

import java.io.IOException
import java.time.ZonedDateTime

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Sync}
import cats.implicits._
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ListObjectsRequest, ObjectListing, S3Object, S3ObjectSummary, ObjectMetadata => AwsObjectMetadata}
import eu.timepit.refined.cats.syntax._
import fs2.{Pipe, Stream}
import mouse.all._
import s3dsl.domain.auth.Domain.{PolicyRead, PolicyWrite}
import s3dsl.domain.S3._
import scala.concurrent.duration.FiniteDuration
import collection.immutable._

trait S3Dsl[F[_]] {

  def createBucket(bucket: BucketName): F[Unit]
  def deleteBucket(bucket: BucketName): F[Unit]
  def doesBucketExist(bucket: BucketName): F[Boolean]
  def listBuckets: F[List[BucketName]]

  def getBucketAcl(bucket: BucketName): F[Option[AccessControlList]]
  def getBucketPolicy(bucket: BucketName): F[Option[PolicyRead]]
  def setBucketPolicy(bucket: BucketName, policy: PolicyWrite): F[Unit]


  def getObjectMetadata(path: Path): F[Option[ObjectMetadata]]
  def getObject(path: Path, chunkSize: Int): Stream[F, Byte]
  def doesObjectExist(path: Path): F[Boolean]
  def listObjects(path: Path): Stream[F, ObjectSummary]
  def listObjectsWithCommonPrefixes(path: Path): Stream[F, ObjectSummary Either CommonPrefix]
  def putObject(path: Path, contentLength: Long): Pipe[F, Byte, Unit]
  def putObjectWithHeaders(path: Path, contentLength: Long, headers: List[(String, String)]): Pipe[F, Byte, Unit]
  def copyObject(src: Path, dest: Path): F[Unit]
  def deleteObject(path: Path): F[Unit]

  def generatePresignedUrl(path: Path, expiration: ZonedDateTime, method: HTTPMethod): F[URL]
}

object S3Dsl {
  import collection.JavaConverters._
  import java.io.InputStream
  import io.circe.syntax._


  final case class S3Config(creds: AWSCredentials,
                            endpoint: EndpointConfiguration,
                            connectionTTL: Option[FiniteDuration])

  def interpreter[F[_]](config: S3Config, cs: ContextShift[F], blocker: Blocker)(implicit F: ConcurrentEffect[F]): S3Dsl[F] = {

    val s3 = createAwsS3Client(config)

    implicit class SyncSyntax(val sync: Sync[F]) {
      def blocking[A](fa: => A): F[A] = cs.blockOn(blocker)(sync.delay(fa))
    }

    new S3Dsl[F] {

      //
      // Bucket
      //

      override def doesBucketExist(name: BucketName): F[Boolean] = listBuckets.map(_.contains(name))
        // s3.doesBucketExistV2(name.value) does not work with minio

      override def createBucket(name: BucketName): F[Unit] = F.blocking(s3.createBucket(name.value)).void

      override def deleteBucket(name: BucketName): F[Unit] = F.blocking(s3.deleteBucket(name.value)).void

      override def listBuckets: F[List[BucketName]] =
        F.blocking(s3.listBuckets).map(_.asScala.toList.map(b => bucketNameOrErr(b.getName)))

      //
      // Bucket ACL
      //

      override def getBucketAcl(name: BucketName): F[Option[AccessControlList]] = {
        import com.amazonaws.services.s3.model.{AccessControlList => AwsAcl, Permission => AwsPermission}

        def fromAws(aws: AwsAcl): AccessControlList = {
          val awsGrants = aws.getGrantsAsList.asScala.toList

          val grants = awsGrants.map{ g =>
            val grantee = Grantee(
              // minio returns null for a grantee that is supposed to be the owner itself
              identifier = Grantee.Identifier(Option(g.getGrantee.getIdentifier).getOrElse("")),
              typeIdentifier = Grantee.TypeIdentifier(g.getGrantee.getTypeIdentifier),
            )

            val permission: Permission = g.getPermission match {
              case p if p.name === AwsPermission.FullControl.name => Permission.FullControl
              case p if p.name === AwsPermission.Read.name => Permission.Read
              case p if p.name === AwsPermission.ReadAcp.name => Permission.ReadAcp
              case p if p.name === AwsPermission.Write.name => Permission.Write
              case p if p.name === AwsPermission.WriteAcp.name => Permission.WriteAcp
            }

            Grant(grantee, permission)
          }

          val owner = Owner(
            id = Owner.Id(aws.getOwner.getId), // "" with minio
            displayName = Owner.DisplayName(aws.getOwner.getDisplayName) // "" with minio
          )

          AccessControlList(grants, owner)
        }

        F.blocking(Option(s3.getBucketAcl(name.value)).map(fromAws)).handle404(None)
      }

      //
      // Bucket Policy
      //

      override def getBucketPolicy(name: BucketName): F[Option[PolicyRead]] = {
        import io.circe.parser.parse
        F.blocking(s3.getBucketPolicy(name.value)).map(aws =>
          Option(aws.getPolicyText)
            .map(s => parse(s).flatMap(_.as[PolicyRead]))
            .map(e => e.fold(l => sys.error(l.getMessage), identity))
        )
      }

      override def setBucketPolicy(bucket: BucketName, policy: PolicyWrite): F[Unit] =
        F.blocking(s3.setBucketPolicy(bucket.value, policy.asJson.noSpaces))

      //
      // Object
      //

      override def getObject(path: Path, chunkSize: Int): Stream[F, Byte] = {

        val acquire = F.blocking[Option[S3Object]](Some(s3.getObject(path.bucket.value, path.key.value))).handle404(None)
        val release: Option[S3Object] => F[Unit] = _.traverse_ { obj =>
          for {
            shouldAbort <- F.blocking(obj.getObjectContent.getDelegateStream.available =!= 0).recover { case _: IOException => true }
            _ <- F.blocking(shouldAbort.fold(obj.getObjectContent.abort, obj.close)).attempt.void
          } yield ()
        }

        fs2.Stream
          .bracket(acquire)(release)
          .flatMap( _.traverse(s3Object =>
            // s3Object.getObjectContent InputStream will be closed via fs2.Stream.bracket, that's why closeAfterUse = false
              fs2.io.readInputStream[F](
                F.blocking[InputStream](s3Object.getObjectContent), chunkSize, blocker, closeAfterUse = false
              )(F, cs)
            ).unNone
          )
      }

      override def getObjectMetadata(path: Path): F[Option[ObjectMetadata]] = F.blocking(
        Some(s3.getObjectMetadata(path.bucket.value, path.key.value)).map(toMeta)
      ).handle404(None)

      override def doesObjectExist(path: Path): F[Boolean] = F.blocking(
        s3.doesObjectExist(path.bucket.value, path.key.value)
      )

      override def listObjects(path: Path): Stream[F, ObjectSummary] =
        listObjectsWithCommonPrefixes(path).collect {
          case Left(objectSummary) => objectSummary
        }

      override def listObjectsWithCommonPrefixes(path: Path): Stream[F, ObjectSummary Either CommonPrefix] = {
        def next(ol: ObjectListing): F[Option[(ObjectListing, ObjectListing)]] = Option(ol)
          .flatMap(_.isTruncated.option(ol))
          .flatTraverse(ol => F.blocking(Option(s3.listNextBatchOfObjects(ol)).map(ol => (ol, ol)))
        )
        val first = F.blocking(
          Option(s3.listObjects(new ListObjectsRequest(path.bucket.value, path.key.value, "", "/", 1000)))
        ).handle404(None)

        Stream.eval(first)
          .flatMap(frst => frst.cata(
            ol => Stream.emit(ol).covary[F] ++ Stream.unfoldEval(ol)(next),
            Stream.empty.covary[F])
          )
          .evalMap(objectListing =>
            (
              Sync[F].delay(objectListing.getObjectSummaries.asScala.toList),
              Sync[F]
                .delay(objectListing.getCommonPrefixes.asScala.toList)
                .flatMap(_.traverse(Key.from(_).leftMap(new Exception(_) : Throwable).liftTo[F]))
            )
            .mapN( (objectSummaries, commonPrefixes) =>
              List(
                  objectSummaries.map(toSummary).map(_.asLeft[CommonPrefix]),
                  commonPrefixes.map(CommonPrefix(_)).map(_.asRight[ObjectSummary])
              ).combineAll
            )
          )
          .flatMap(Stream.emits)
      }

      override def putObject(path: Path, contentLength: Long): Pipe[F, Byte, Unit] = { fs2In =>
        fs2.io.toInputStream(F)(fs2In).through(FS2.liftPipe(putObj(path, contentLength, Nil)))
      }

      override def putObjectWithHeaders(path: Path,
                                        contentLength: Long,
                                        headers: List[(String, String)]): Pipe[F, Byte, Unit] = { fs2In =>
        fs2.io.toInputStream(F)(fs2In).through(FS2.liftPipe(putObj(path, contentLength, headers)))
      }

      override def copyObject(src: Path, dest: Path): F[Unit] = F.blocking(
        s3.copyObject(src.bucket.value, src.key.value, dest.bucket.value, dest.key.value)
      ).void

      override def deleteObject(path: Path): F[Unit] = F.blocking(
        s3.deleteObject(path.bucket.value, path.key.value)
      ).handle404(())

      //
      // Presigned URL
      //

      // FIXME: Requests that are pre-signed by SigV4 algorithm are valid for at most 7 days
      override def generatePresignedUrl(path: Path, expiration: ZonedDateTime, method: HTTPMethod): F[URL] = F.delay(
        URL(
          s3.generatePresignedUrl(
            path.bucket.value,
            path.key.value,
            java.util.Date.from(expiration.toInstant),
            method.aws
          ).toString)
      )


      private def putObj(path: Path, contentLength: Long, headers: List[(String, String)])
                        (is: InputStream): F[Unit] = {
        val meta = new AwsObjectMetadata
        meta.setContentLength(contentLength)

        (for {
          _ <- Stream.emits(headers).evalTap(t2 => F.delay(meta.setHeader(t2._1, t2._2))).compile.drain
          _ <- F.blocking(s3.putObject(path.bucket.value, path.key.value, is, meta))
        } yield ()).void
      }

    }
  }

  private def bucketNameOrErr(s: String): BucketName = BucketName.validate(s).fold(
    l => sys.error(s"Programming error in bucket name validation: ${l.show}"), identity
  )

  private def keyOrErr(s: String): Key = Key.validate(s).fold(
    l => sys.error(s"Programming error in s3 key validation: ${l.show}"), identity
  )


  private def toSummary(aws: S3ObjectSummary): ObjectSummary = {
    val path = Path(bucketNameOrErr(aws.getBucketName), keyOrErr(aws.getKey))
    val etag = Option(aws.getETag).map(ETag.apply)
    val storClass = Option(aws.getStorageClass).map(StorageClass.apply)
    val lastModified = Option(aws.getLastModified).map(LastModified.apply)
    ObjectSummary(path, aws.getSize, etag, storClass, lastModified)
  }

  private def toMeta(aws: AwsObjectMetadata): ObjectMetadata = {
    val contentType = Option(aws.getContentType).map(ContentType.apply)
    val md5 = Option(aws.getContentMD5).map(MD5.apply)
    val etag = Option(aws.getETag).map(ETag.apply)
    val expiration = Option(aws.getExpirationTime).map(ExpirationTime.apply)
    val lastModified = Option(aws.getLastModified).map(LastModified.apply)
    val userMetadata = Option(aws.getUserMetadata.asScala.toMap).getOrElse(Map.empty[String, String])
    ObjectMetadata(contentType, aws.getContentLength, md5, etag, expiration, lastModified, userMetadata)
  }

  private def createAwsS3Client(config: S3Config): AmazonS3 = {
    import com.amazonaws.ClientConfiguration
    import com.amazonaws.auth.AWSStaticCredentialsProvider
    import com.amazonaws.services.s3.AmazonS3ClientBuilder

    val clientConfiguration = new ClientConfiguration()
      .withConnectionTTL(
        config.connectionTTL.map(_.toMillis)
          .getOrElse(ClientConfiguration.DEFAULT_CONNECTION_TTL)
      )

    clientConfiguration
      .setSignerOverride("AWSS3V4SignerType")

    AmazonS3ClientBuilder.standard()
      .withEndpointConfiguration(config.endpoint)
      .withPathStyleAccessEnabled(true)
      .withClientConfiguration(clientConfiguration)
      .withCredentials(new AWSStaticCredentialsProvider(config.creds))
      .build()

  }

}



