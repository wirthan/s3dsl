package s3dsl

import java.time.ZonedDateTime

import s3dsl.Dsl.S3Dsl.S3Config
import s3dsl.domain.S3._
import com.amazonaws.services.s3.model.{AmazonS3Exception, ListObjectsRequest, ObjectListing, S3Object, S3ObjectSummary, ObjectMetadata => AwsObjectMetadata}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import eu.timepit.refined.cats.syntax._
import fs2.{Sink, Stream}
import cats.instances.all._
import cats.syntax.all._
import mouse.all._
import cats.effect.{ConcurrentEffect, ContextShift, Sync}

object Dsl {

  trait S3Dsl[F[_]] {

    def createBucket(name: BucketName): F[Unit]
    def deleteBucket(name: BucketName): F[Unit]
    def doesBucketExist(name: BucketName): F[Boolean]
    def listBuckets: F[List[BucketName]]

    def getBucketAcl(name: BucketName): F[Option[AccessControlList]]

    def getObject(path: Path, chunkSize: Int): F[Option[Object[F]]]
    def getObjectMetadata(path: Path): F[Option[ObjectMetadata]]
    def doesObjectExist(path: Path): F[Boolean]
    def listObjects(path: Path): Stream[F, ObjectSummary]
    def putObject(path: Path, contentLength: Long): Sink[F, Byte]
    def deleteObject(path: Path): F[Unit]

    def generatePresignedUrl(path: Path, expiration: ZonedDateTime, method: HTTPMethod): F[URL]
  }

  object S3Dsl {
    import scala.concurrent.ExecutionContext
    import collection.JavaConverters._
    import java.io.InputStream

    final case class S3Config(creds: AWSCredentials,
                              endpoint: EndpointConfiguration,
                              blockingEc: ExecutionContext)

    def interpreter[F[_]](config: S3Config)(implicit F: ConcurrentEffect[F], cs: ContextShift[F]): S3Dsl[F] = {

      val s3 = createAwsS3Client(config)
      val blockingEc = config.blockingEc

      implicit class SyncSyntax(val sync: Sync[F]) {
        def blocking[A](fa: => A): F[A] = cs.evalOn(blockingEc)(sync.delay(fa))
      }

      implicit class OptionFSyntax[A](val f: F[Option[A]]) {
        def handle404: F[Option[A]] = f.recoverWith{ case e: AmazonS3Exception =>
          e.getStatusCode match {
            case 404 => F.pure(None)
            case _ => F.raiseError(e)
          }
        }
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

          F.blocking(Option(s3.getBucketAcl(name.value)).map(fromAws)).handle404
        }

        //
        // Object
        //

        override def getObject(path: Path, chunkSize: Int): F[Option[Object[F]]] = for {
          s3object <- F.blocking[Option[S3Object]](Some(s3.getObject(path.bucket.value, path.key.value))).handle404
          obj <- s3object.traverse { o =>
            val isT: F[InputStream] = F.blocking(o.getObjectContent)
            F.delay(Object[F](
              stream = fs2.io.readInputStream[F](isT, chunkSize, blockingEc, closeAfterUse = true),
              meta = toMeta(o.getObjectMetadata))
            )
          }
        } yield obj

        override def getObjectMetadata(path: Path): F[Option[ObjectMetadata]] = F.blocking(
          Some(s3.getObjectMetadata(path.bucket.value, path.key.value)).map(toMeta)
        ).handle404

        override def doesObjectExist(path: Path): F[Boolean] = F.blocking(
          s3.doesObjectExist(path.bucket.value, path.key.value)
        )

        override def listObjects(path: Path): Stream[F, ObjectSummary] = {

          def next(ol: ObjectListing): F[Option[(ObjectListing, ObjectListing)]] = Option(ol)
            .flatMap(_.isTruncated.option(ol))
            .flatTraverse(ol => F.blocking(Option(s3.listNextBatchOfObjects(ol)).map(ol => (ol, ol)))
          )
          val first = F.blocking(s3.listObjects(new ListObjectsRequest(path.bucket.value, path.key.value, "", "/", 1000)))

          Stream.eval(first)
            .flatMap(frst => Option(frst).cata(
              ol => Stream.emit(ol).covary[F] ++ Stream.unfoldEval(ol)(next),
              Stream.empty.covary[F])
            )
            .flatMap(l => Stream.emits(l.getObjectSummaries.asScala))
            .map(toSummary)
        }

        override def putObject(path: Path, contentLength: Long): Sink[F, Byte] = { fs2In =>

          def putObject(is: InputStream):F[Unit] = F.blocking {
            val meta = new AwsObjectMetadata
            meta.setContentLength(contentLength)
            s3.putObject(path.bucket.value, path.key.value, is, meta)
          }.void

          fs2.io.toInputStream(F)(fs2In).to(FS2.liftSink(putObject))
        }

        override def deleteObject(path: Path): F[Unit] = F.blocking(s3.deleteObject(path.bucket.value, path.key.value))

        //
        // Presigned URL
        //

        override def generatePresignedUrl(path: Path, expiration: ZonedDateTime, method: HTTPMethod): F[URL] = F.delay(
          URL(
            s3.generatePresignedUrl(
              path.bucket.value,
              path.key.value,
              new java.util.Date(expiration.getNano / 1000000L),
              method.aws
            ).toString)
        )

      }
    }

  }


  private def bucketNameOrErr(s: String): BucketName = BucketName.validate(s).fold(
    l => sys.error(s"Programming error in bucket name validation: $l"), identity
  )

  private def keyOrErr(s: String): Key = Key.validate(s).fold(
    l => sys.error(s"Programming error in s3 key validation: $l"), identity
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
    ObjectMetadata(contentType, aws.getContentLength, md5, etag, expiration, lastModified)
  }

  private def createAwsS3Client(config: S3Config): AmazonS3 = {
    import com.amazonaws.ClientConfiguration
    import com.amazonaws.auth.AWSStaticCredentialsProvider
    import com.amazonaws.services.s3.AmazonS3ClientBuilder

    val clientConfiguration = new ClientConfiguration
    clientConfiguration.setSignerOverride("AWSS3V4SignerType")

    AmazonS3ClientBuilder.standard()
      .withEndpointConfiguration(config.endpoint)
      .withPathStyleAccessEnabled(true)
      .withClientConfiguration(clientConfiguration)
      .withCredentials(new AWSStaticCredentialsProvider(config.creds))
      .build()

  }

}
