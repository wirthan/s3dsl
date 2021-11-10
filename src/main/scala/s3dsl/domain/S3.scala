package s3dsl.domain

import com.amazonaws.services.s3.model.{Permission => AwsPermission}
import com.amazonaws.{HttpMethod => AwsHttpMethod}
import enumeratum.EnumEntry.Uppercase
import enumeratum.{Enum, EnumEntry}
import eu.timepit.refined.api._
import io.estatico.newtype.macros.newtype
import java.util.Date

@SuppressWarnings(Array(
  "org.wartremover.warts.ExplicitImplicitTypes",
  "org.wartremover.warts.ImplicitConversion",
  "org.wartremover.warts.ImplicitParameter"))
object S3 {

  @newtype final case class URL(value: String)

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

  @newtype final case class CommonPrefix(value: Key)

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
                                  lastModified: Option[LastModified],
                                  userMedata: Map[String, String])

  final case class ObjectSummary(path: Path,
                                 size: Long,
                                 etag: Option[ETag],
                                 storageClass: Option[StorageClass],
                                 lastModified: Option[LastModified])

  final case class ObjectTags(value: Map[String, String])

  //
  // Access control
  //

  final case class Owner(id: Owner.Id, displayName: Owner.DisplayName)

  object Owner {
    @newtype final case class Id(value: String)
    @newtype final case class DisplayName(value: String)
  }

  final case class Grantee(identifier: Grantee.Identifier, typeIdentifier: Grantee.TypeIdentifier)

  object Grantee {
    @newtype final case class Identifier(value: String)
    @newtype final case class TypeIdentifier(value: String)
  }

  sealed trait Permission extends EnumEntry {
    import s3dsl.domain.S3.Permission._

    private[s3dsl] lazy val aws: AwsPermission = fold(
      fullControl = AwsPermission.FullControl,
      read = AwsPermission.Read,
      readAcp = AwsPermission.ReadAcp,
      write = AwsPermission.Write,
      writeAcp = AwsPermission.WriteAcp
    )

    def fold[X](fullControl: => X, read: => X, readAcp: => X, write: => X, writeAcp: => X): X = this match {
      case FullControl => fullControl
      case Read => read
      case ReadAcp => readAcp
      case Write => write
      case WriteAcp => writeAcp
    }

  }

  object Permission extends Enum[Permission] {
    lazy val values = findValues

    final case object FullControl extends Permission
    final case object Read extends Permission
    final case object ReadAcp extends Permission
    final case object Write extends Permission
    final case object WriteAcp extends Permission
  }

  final case class Grant(grantee: Grantee, permission: Permission)

  final case class AccessControlList(grants: List[Grant], owner: Owner)

  //
  // HTTP Method
  //

  sealed trait HTTPMethod extends EnumEntry {
    import s3dsl.domain.S3.HTTPMethod._

    private[s3dsl] lazy val aws: AwsHttpMethod = fold(
      delete = AwsHttpMethod.DELETE,
      get = AwsHttpMethod.GET,
      post = AwsHttpMethod.POST,
      put = AwsHttpMethod.PUT
    )

    def fold[X](delete: => X, get: => X, post: => X, put: => X): X = this match {
      case DELETE => delete
      case GET => get
      case POST => post
      case PUT => put
    }
  }

  object HTTPMethod extends Enum[HTTPMethod] {
    lazy val values = findValues

    final case object DELETE extends HTTPMethod with Uppercase
    final case object GET extends HTTPMethod with Uppercase
    final case object POST extends HTTPMethod with Uppercase
    final case object PUT extends HTTPMethod with Uppercase
  }

}
