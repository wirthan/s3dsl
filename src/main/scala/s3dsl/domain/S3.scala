package s3dsl.domain

import java.util.Date
import eu.timepit.refined.api._
import fs2.Stream
import io.estatico.newtype.macros.newtype

@SuppressWarnings(Array(
  "org.wartremover.warts.ExplicitImplicitTypes",
  "org.wartremover.warts.ImplicitConversion",
  "org.wartremover.warts.ImplicitParameter"))
object S3 {

  //
  // Bucket name & object key
  //

  type BucketName = String Refined refined.BucketName
  object BucketName extends RefinedTypeOps[BucketName, String]

  type Key = String Refined refined.Key
  object Key extends RefinedTypeOps[Key, String] {
    lazy val empty: Key = unsafeFrom("")
  }

  final case class Path(bucket: BucketName, key: Key) {
    lazy val isDir = key.value.endsWith("/")
    override def toString: String = s"${bucket.value}/${key.value}"
  }


  //
  // Object & Object metadata
  //

  final case class Object[F[_]](stream: Stream[F, Byte], meta: ObjectMetadata)

  @newtype final case class ContentType(value: String)
  @newtype final case class MD5(value: String)
  @newtype final case class ETag(value: String)
  @newtype final case class ExpirationTime(value: Date)
  @newtype final case class LastModified(value: Date)
  @newtype final case class StorageClass(value: String)

  final case class ObjectMetadata(contentType: Option[ContentType],
                                  contentLength: Long,
                                  md5: Option[MD5],
                                  etag: Option[ETag],
                                  expirationTime: Option[ExpirationTime],
                                  lastModified: Option[LastModified])

  final case class ObjectSummary(path: Path,
                                 size: Long,
                                 etag: Option[ETag],
                                 storageClass: Option[StorageClass],
                                 lastModified: Option[LastModified])



}
