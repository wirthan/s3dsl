package s3dsl

import cats.effect.Async
import cats.effect.kernel.Sync
import cats.implicits._
import eu.timepit.refined.cats.syntax._
import fs2.Pipe
import fs2.Stream
import fs2.interop._
import io.circe.syntax._
import s3dsl.domain.S3._
import s3dsl.domain.auth.Domain.PolicyRead
import s3dsl.domain.auth.Domain.PolicyWrite
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.services.s3.model.Tag
import software.amazon.awssdk.transfer.s3.S3TransferManager

import java.nio.ByteBuffer
import java.util.Date
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._

import collection.immutable._
import cats.effect.kernel.Resource

trait S3Dsl[F[_]] {

  def createBucket(bucket: BucketName): F[Unit]
  def deleteBucket(bucket: BucketName): F[Unit]
  def doesBucketExist(bucket: BucketName): F[Boolean]
  def listBuckets: F[List[BucketName]]

  def getBucketAcl(bucket: BucketName): F[Option[AccessControlList]]
  def getBucketPolicy(bucket: BucketName): F[Option[PolicyRead]]
  def setBucketPolicy(bucket: BucketName, policy: PolicyWrite): F[Unit]

  def getObject(path: Path, chunkSize: Int): Stream[F, Byte]
  final def listObjects(path: Path): Stream[F, ObjectSummary] =
    listObjectsWithCommonPrefixes(path).collect {
      case Left(a) => a
    }
  def listObjectsWithCommonPrefixes(path: Path): Stream[F, ObjectSummary Either CommonPrefix]
  def putObject(path: Path, contentLength: Long, headers: List[(String, String)]): Pipe[F, Byte, Unit]
  def copyObject(src: Path, dest: Path): F[Unit]
  def copyObjectMultipart(src: Path, dest: Path, partSizeBytes: Long): F[Unit]
  def deleteObject(path: Path): F[Unit]

  def getObjectTags(path: Path): F[Option[ObjectTags]]
  def setObjectTags(path: Path, tags: ObjectTags): F[Unit]

  def getObjectMetadata(path: Path): F[Option[ObjectMetadata]]
}

object S3Dsl {

  def interpreter[
  F[_] : Async
  ](client: S3AsyncClient): S3Dsl[F]  = new S3Dsl[F] {

    private val maxTcpPayloadSize = 65535

    //
    // Bucket
    //

    override def doesBucketExist(name: BucketName): F[Boolean] =
      listBuckets.map(_.contains(name))
      // s3.doesBucketExistV2(name.value) does not work with minio

    override def createBucket(name: BucketName): F[Unit] = Async[F]
      .fromFuture(Sync[F].delay(client.createBucket(_.bucket(name.value)).asScala))
      .void

    override def deleteBucket(name: BucketName): F[Unit] = Async[F]
      .fromFuture(Sync[F].delay(client.deleteBucket(_.bucket(name.value)).asScala))
      .void

    override def listBuckets: F[List[BucketName]] = Async[F]
      .fromFuture(Sync[F].delay(client.listBuckets.asScala))
      .map(_.buckets.asScala.toList.map(b => bucketNameOrErr(b.name)))

    //
    // Bucket ACL
    //

    override def getBucketAcl(name: BucketName): F[Option[AccessControlList]] = {
      import software.amazon.awssdk.services.s3.model.{Permission => AwsPermission}

      def fromAws(aws: GetBucketAclResponse): AccessControlList = {
        val awsGrants = aws.grants.asScala.toList
        val grants = awsGrants.map{ g =>
          val grantee = Grantee(
            // minio returns null for a grantee that is supposed to be the owner itself
            identifier = Grantee.Identifier(Option(g.grantee.id).getOrElse("")),
            typeIdentifier = Grantee.TypeIdentifier(g.grantee.typeAsString),
          )

          val permission: Permission = g.permission match {
            case AwsPermission.FULL_CONTROL => Permission.FullControl
            case AwsPermission.READ=> Permission.Read
            case AwsPermission.READ_ACP => Permission.ReadAcp
            case AwsPermission.WRITE => Permission.Write
            case AwsPermission.WRITE_ACP => Permission.WriteAcp
            case AwsPermission.UNKNOWN_TO_SDK_VERSION => Permission.Read
          }

          Grant(grantee, permission)
        }

        val owner = Owner(
          id = Owner.Id(aws.owner.id), // "" with minio
          displayName = Owner.DisplayName(aws.owner.displayName) // "" with minio
        )

        AccessControlList(grants, owner)
      }
      
      Async[F]
        .fromFuture(Sync[F].delay(client.getBucketAcl(_.bucket(name.value)).asScala))
        .map(fromAws)
        .map(_.some)
        .handle404(None)
        
    }

    //
    // Bucket Policy
    //

    override def getBucketPolicy(name: BucketName): F[Option[PolicyRead]] = {
      import io.circe.parser.parse
      Async[F]
        .fromFuture(Sync[F].delay(client.getBucketPolicy(_.bucket(name.value)).asScala))
        .flatMap(aws =>
          Option
            .apply(aws.policy)
            .map(s => parse(s).flatMap(_.as[PolicyRead]))
            .traverse(_.liftTo[F])
        )
    }

    override def setBucketPolicy(bucket: BucketName, policy: PolicyWrite): F[Unit] =
      Async[F]
        .fromFuture(Sync[F].delay(client.putBucketPolicy(_.bucket(bucket.value).policy(policy.asJson.noSpaces)).asScala))
        .void

    //
    // Object
    //

    override def getObject(path: Path, chunkSize: Int): Stream[F, Byte] = 
      Stream.eval(
        Async[F].fromFuture( 
          Sync[F].delay(
            client.getObject(
              GetObjectRequest.builder.bucket(path.bucket.value).key(path.key.value).build,
              AsyncResponseTransformer.toPublisher[GetObjectResponse]
            ).asScala
          )
        )
      )
      .flatMap( s3request => fs2.interop.reactivestreams.fromPublisher(s3request,chunkSize) )
      .flatMap( b => Stream.emits(b.array()) )
      .map(Option.apply)
      .handle404(None)
      .unNone
    
    override def getObjectMetadata(path: Path): F[Option[ObjectMetadata]] = 
      Async[F].fromFuture(
        Sync[F].delay(
          client.headObject(_.bucket(path.bucket.value).key(path.key.value)
        ).asScala)
      )
      .map(toMeta)
      .map(_.some)
      .handle404(None)

    override def listObjectsWithCommonPrefixes(path: Path): Stream[F, ObjectSummary Either CommonPrefix] =
      Stream.eval(
        Sync[F].delay(
          client.listObjectsV2Paginator(_.bucket(path.bucket.value).prefix(path.key.value).maxKeys(1000))
        )
      )
      .flatMap(reactivestreams.fromPublisher(_, 1000))
      .evalMap(response =>
        (
          Sync[F].delay(response.contents.asScala.toList),
          Sync[F].delay(response.commonPrefixes.asScala.toList)
          .flatMap(_.traverse(p => Key.from(p.prefix).leftMap(new Exception(_) : Throwable).liftTo[F]))
        )
        .mapN((objects, commonPrefixes) =>
          List(
              objects.map(toSummary(_, path.bucket)).map(_.asLeft[CommonPrefix]),
              commonPrefixes.map(CommonPrefix(_)).map(_.asRight[ObjectSummary])
          ).combineAll
        )
      )
      .flatMap(Stream.emits)

    override def putObject(path: Path,
                           contentLength: Long,
                           headers: List[(String, String)]): Pipe[F, Byte, Unit] =  fs2In => {

      lazy val req = PutObjectRequest
        .builder
        .bucket(path.bucket.value)
        .key(path.key.value)
        .metadata(headers.toMap.asJava)
        .contentLength(contentLength)
        .build

      def createBody(s: Stream[F, Byte]): Resource[F, AsyncRequestBody] =
        reactivestreams.StreamUnicastPublisher[F, ByteBuffer](
          s.chunkN(maxTcpPayloadSize, true).map(chunks => ByteBuffer.wrap(chunks.toArray).position(0))
        ).map(AsyncRequestBody.fromPublisher)

      Stream.resource(createBody(fs2In))
        .evalMap(body =>
          Async[F].fromFuture(
            Sync[F].delay(client.putObject(req, body).asScala)
          )
        ).void
    }
    override def copyObject(src: Path, dest: Path): F[Unit] = Async[F].fromFuture(
      Sync[F].delay(
        client.copyObject(
          _
          .sourceBucket(src.bucket.value)
          .sourceKey(src.key.value)
          .destinationBucket(dest.bucket.value)
          .destinationKey(dest.key.value)
        ).asScala
      )
    ).void

    override def copyObjectMultipart(src: Path, dest: Path, partSizeBytes: Long): F[Unit] = for {
      tm <- Sync[F].delay(
        S3TransferManager
          .builder
          .s3Client(client)
          .build
      )
      _ <- Async[F].fromFuture(
        Sync[F].delay(
          tm
            .copy(_
              .copyObjectRequest(
                _
                .sourceBucket(src.bucket.value)
                .sourceKey(src.key.value)
                .destinationBucket(dest.bucket.value)
                .destinationKey(dest.key.value)
              )
            )
            .completionFuture
            .asScala
        )
      )
    } yield ()

    override def deleteObject(path: Path): F[Unit] = Async[F]
      .fromFuture(
        Sync[F].delay(
          client.deleteObject(_.bucket(path.bucket.value).key(path.key.value)).asScala
        )
      )
      .void
      .handle404(())

    override def getObjectTags(path: Path): F[Option[ObjectTags]] =
      Async[F]
        .fromFuture(
          Sync[F].delay(client.getObjectTagging(_.bucket(path.bucket.value).key(path.key.value)).asScala)
        )
        .map(_.tagSet.asScala.map(t => t.key -> t.value).toMap)
        .map(ObjectTags)
        .map(Option.apply)
        .handle404(None)

    override def setObjectTags(path: Path, tags: ObjectTags): F[Unit] =
      Async[F]
        .fromFuture(
          Sync[F].delay(
            client
              .putObjectTagging(
                _
                .bucket(path.bucket.value)
                .key(path.key.value)
                .tagging(_
                  .tagSet(
                    tags.value.map{case (k, v) => Tag.builder.key(k).value(v).build}.toList.asJava
                  )
                )
              )
              .asScala
          )
        )
        .void
  }

  private def bucketNameOrErr(s: String): BucketName = BucketName.validate(s).fold(
    l => sys.error(s"Programming error in bucket name validation: ${l.show}"), identity
  )

  private def keyOrErr(s: String): Key = Key.validate(s).fold(
    l => sys.error(s"Programming error in s3 key validation: ${l.show}"), identity
  )

  private def toSummary(aws: S3Object, bucketName: BucketName): ObjectSummary = {
    val path = Path(bucketNameOrErr(bucketName.value), keyOrErr(aws.key))
    val etag = Option(aws.eTag).map(ETag.apply)
    val storClass = Option(aws.storageClass.name).map(StorageClass.apply)
    val lastModified = Option(aws.lastModified).map(Date.from).map(LastModified.apply)
    ObjectSummary(path, aws.size.longValue(), etag, storClass, lastModified)
  }

  private def toMeta(aws: HeadObjectResponse): ObjectMetadata = {
    val contentType = Option(aws.contentEncoding).map(ContentType.apply)
    val md5 = Option(aws.sseCustomerKeyMD5).map(MD5.apply)
    val etag = Option(aws.eTag).map(ETag.apply)
    val expiration = Option(aws.expires).map(Date.from).map(ExpirationTime.apply)
    val lastModified = Option(aws.lastModified).map(Date.from).map(LastModified.apply)
    val userMetadata = Option(aws.metadata.asScala.toMap).getOrElse(Map.empty[String, String])
    ObjectMetadata(contentType, aws.contentLength.longValue(), md5, etag, expiration, lastModified, userMetadata)
  }
}
