package s3dsl

import cats.effect.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._
import collection.immutable._
import fs2.interop._
import fs2.Pipe
import fs2.Stream
import io.circe.syntax._
import java.nio.ByteBuffer
import s3dsl.domain.auth.Domain.PolicyRead
import s3dsl.domain.auth.Domain.PolicyWrite
import s3dsl.domain.S3._
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.model.{Bucket, CommonPrefix, GetBucketAclResponse, GetObjectRequest, GetObjectResponse, HeadObjectResponse, ListObjectsV2Request, PutObjectRequest, S3Object, Tag}
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Publisher
import software.amazon.awssdk.services.s3.S3AsyncClient

trait S3Dsl[F[_]] {

  def createBucket(bucket: BucketName): F[Unit]
  def deleteBucket(bucket: BucketName): F[Unit]
  def doesBucketExist(bucket: BucketName): F[Boolean]
  def listBuckets: F[List[Bucket]]

  def getBucketAcl(bucket: BucketName): F[Option[AccessControlList]]
  def getBucketPolicy(bucket: BucketName): F[Option[PolicyRead]]
  def setBucketPolicy(bucket: BucketName, policy: PolicyWrite): F[Unit]

  def getObject(path: Path, chunkSize: Int): Stream[F, Byte]
  def listObjects(bucketName: BucketName): Stream[F, S3Object]
  def listObjectsWithCommonPrefixes(bucketName: BucketName, prefix: String): Stream[F, Either[S3Object, CommonPrefix]]
  def putObject(path: Path, contentLength: Long, headers: List[(String, String)]): Pipe[F, Byte, Unit]
  def copyObject(src: Path, dest: Path): F[Unit]
  def deleteObject(path: Path): F[Unit]

  def getObjectTags(path: Path): F[Option[ObjectTags]]
  def setObjectTags(path: Path, tags: ObjectTags): F[Unit]

  def headObject(path: Path): F[Option[HeadObjectResponse]]
}

object S3Dsl {

  def interpreter[
  F[_] : Async
  ](client: S3AsyncClient): S3Dsl[F]  = new S3Dsl[F] {

    private val maxTcpPayloadSize = 65535

    //
    // Bucket
    //

    override def doesBucketExist(name: BucketName): F[Boolean] = listBuckets
      .map(_.map(_.name))
      .map(_.contains_(name.value))
      // s3.doesBucketExistV2(name.value) does not work with minio

    override def createBucket(name: BucketName): F[Unit] = Async[F]
      .fromCompletableFuture(Sync[F].delay(client.createBucket(_.bucket(name.value))))
      .void

    override def deleteBucket(name: BucketName): F[Unit] = Async[F]
      .fromCompletableFuture(Sync[F].delay(client.deleteBucket(_.bucket(name.value))))
      .void

    override def listBuckets: F[List[Bucket]] = Async[F]
      .fromCompletableFuture(Sync[F].delay(client.listBuckets))
      .map(_.buckets.asScala.toList)

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
        .fromCompletableFuture(Sync[F].delay(client.getBucketAcl(_.bucket(name.value))))
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
        .fromCompletableFuture(Sync[F].delay(client.getBucketPolicy(_.bucket(name.value))))
        .flatMap(aws =>
          Option
            .apply(aws.policy)
            .map(s => parse(s).flatMap(_.as[PolicyRead]))
            .traverse(_.liftTo[F])
        )
    }

    override def setBucketPolicy(bucket: BucketName, policy: PolicyWrite): F[Unit] =
      Async[F]
        .fromCompletableFuture(Sync[F].delay(client.putBucketPolicy(_.bucket(bucket.value).policy(policy.asJson.noSpaces))))
        .void

    //
    // Object
    //

    override def getObject(path: Path, chunkSize: Int): Stream[F, Byte] =
      Stream.eval(
        Async[F].fromCompletableFuture(
          Sync[F].delay(
            client.getObject(
              GetObjectRequest.builder.bucket(path.bucketName.value).key(path.key.value).build,
              AsyncResponseTransformer.toPublisher[GetObjectResponse]
            )
          )
        )
      )
      .flatMap( s3request => fs2.interop.reactivestreams.fromPublisher(s3request,chunkSize) )
      .flatMap( b => Stream.emits(b.array()) )
      .map(Option.apply)
      .handle404(None)
      .unNone

    override def headObject(path: Path): F[Option[HeadObjectResponse]] = {
      Async[F].fromCompletableFuture(
        Sync[F].delay(
          client.headObject(_.bucket(path.bucketName.value).key(path.key.value))
        )
      )
      .map(_.some)
      .handle404(None)
    }

    override def listObjects(bucketName: BucketName): Stream[F, S3Object] = {
      val req = ListObjectsV2Request.builder().bucket(bucketName.value).build
      listS3Objects(req).map(_.fold(_.some, _ => Option.empty[S3Object])).unNone
    }

    override def listObjectsWithCommonPrefixes(bucketName: BucketName, prefix: String): Stream[F, Either[S3Object, CommonPrefix]] = {
      val req = ListObjectsV2Request.builder().bucket(bucketName.value).prefix(prefix).build
      listS3Objects(req)
    }

    override def putObject(path: Path,
                           contentLength: Long,
                           headers: List[(String, String)]): Pipe[F, Byte, Unit] =  fs2In => {

      lazy val req = PutObjectRequest
        .builder
        .bucket(path.bucketName.value)
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
          Async[F].fromCompletableFuture(
            Sync[F].delay(client.putObject(req, body))
          )
        ).void
    }
    override def copyObject(src: Path, dest: Path): F[Unit] = Async[F].fromCompletableFuture(
      Sync[F].delay(
        client.copyObject(
          _
          .sourceBucket(src.bucketName.value)
          .sourceKey(src.key.value)
          .destinationBucket(dest.bucketName.value)
          .destinationKey(dest.key.value)
        )
      )
    ).void

    override def deleteObject(path: Path): F[Unit] = Async[F]
      .fromCompletableFuture(
        Sync[F].delay(
          client.deleteObject(_.bucket(path.bucketName.value).key(path.key.value))
        )
      )
      .void
      .handle404(())

    override def getObjectTags(path: Path): F[Option[ObjectTags]] =
      Async[F]
        .fromCompletableFuture(
          Sync[F].delay(client.getObjectTagging(_.bucket(path.bucketName.value).key(path.key.value)))
        )
        .map(_.tagSet.asScala.map(t => t.key -> t.value).toMap)
        .map(ObjectTags.apply)
        .map(Option.apply)
        .handle404(None)

    override def setObjectTags(path: Path, tags: ObjectTags): F[Unit] =
      Async[F]
        .fromCompletableFuture(
          Sync[F].delay(
            client
              .putObjectTagging(
                _
                .bucket(path.bucketName.value)
                .key(path.key.value)
                .tagging(_
                  .tagSet(
                    tags.value.map{case (k, v) => Tag.builder.key(k).value(v).build}.toList.asJava
                  )
                )
              )
          )
        )
        .void

    private def listS3Objects(req: ListObjectsV2Request): Stream[F, Either[S3Object, CommonPrefix]] =
      Stream.eval(
        Sync[F].delay[ListObjectsV2Publisher](client.listObjectsV2Paginator(req))
      )
        .flatMap(reactivestreams.fromPublisher(_, 1000))
        .evalMap(response =>
          (
            Sync[F].delay(response.contents.asScala.toList),
            Sync[F].delay(response.commonPrefixes.asScala.toList)
          )
            .mapN((objects, commonPrefixes) =>
              List(
                objects.map(_.asLeft[CommonPrefix]),
                commonPrefixes.map(_.asRight[S3Object])
              ).combineAll
            )
        )
        .flatMap(Stream.emits)
  }

}
