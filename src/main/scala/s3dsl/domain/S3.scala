package s3dsl.domain

import cats.{Eq, Show}
import enumeratum.EnumEntry.Uppercase
import enumeratum.{Enum, EnumEntry}
import software.amazon.awssdk.http.{SdkHttpMethod => AwsHttpMethod}
import software.amazon.awssdk.services.s3.model.{Permission => AwsPermission}

object S3 {

  //
  // Bucket & objects
  //

  final case class BucketName(value: String) extends AnyVal {
    def path(key: Key): Path = Path(this, key)
  }
  object BucketName {
    implicit lazy val show: Show[BucketName] = Show.fromToString
    implicit lazy val eq: Eq[BucketName] = Eq.fromUniversalEquals
  }

  final case class Key(value: String) extends AnyVal
  object Key {
    implicit lazy val show: Show[Key] = Show.fromToString
    implicit lazy val eq: Eq[Key] = Eq.fromUniversalEquals
  }

  final case class Path(bucketName: BucketName, key: Key)
  object Path {
    implicit lazy val show: Show[Path] = Show.fromToString
    implicit lazy val eq: Eq[Path] = Eq.fromUniversalEquals
  }

  final case class ObjectTags(value: Map[String, String])

  object ObjectTags {
    implicit lazy val show: Show[ObjectTags] = Show.fromToString
    implicit lazy val eq: Eq[ObjectTags] = Eq.fromUniversalEquals
  }

  //
  // Access control
  //

  final case class Owner(id: Owner.Id, displayName: Owner.DisplayName)

  object Owner {
    implicit lazy val show: Show[Owner] = Show.fromToString
    implicit lazy val eq: Eq[Owner] = Eq.fromUniversalEquals

    final case class Id(value: String)
    object Id {
      implicit lazy val show: Show[Id] = Show.fromToString
      implicit lazy val eq: Eq[Id] = Eq.fromUniversalEquals
    }

    final case class DisplayName(value: String)
    object DisplayName {
      implicit lazy val show: Show[DisplayName] = Show.fromToString
      implicit lazy val eq: Eq[DisplayName] = Eq.fromUniversalEquals
    }
  }

  final case class Grantee(identifier: Grantee.Identifier, typeIdentifier: Grantee.TypeIdentifier)

  object Grantee {
    implicit lazy val show: Show[Grantee] = Show.fromToString
    implicit lazy val eq: Eq[Grantee] = Eq.fromUniversalEquals

    final case class Identifier(value: String)
    object Identifier {
      implicit lazy val show: Show[Identifier] = Show.fromToString
      implicit lazy val eq: Eq[Identifier] = Eq.fromUniversalEquals
    }

    final case class TypeIdentifier(value: String)
    object TypeIdentifier {
      implicit lazy val show: Show[TypeIdentifier] = Show.fromToString
      implicit lazy val eq: Eq[TypeIdentifier] = Eq.fromUniversalEquals
    }
  }

  sealed trait Permission extends EnumEntry {
    import s3dsl.domain.S3.Permission._

    private[s3dsl] lazy val aws: AwsPermission = fold(
      fullControl = AwsPermission.FULL_CONTROL,
      read = AwsPermission.READ,
      readAcp = AwsPermission.READ_ACP,
      write = AwsPermission.WRITE,
      writeAcp = AwsPermission.WRITE_ACP
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

    implicit lazy val show: Show[Permission] = Show.fromToString
    implicit lazy val eq: Eq[Permission] = Eq.fromUniversalEquals

    final case object FullControl extends Permission
    final case object Read extends Permission
    final case object ReadAcp extends Permission
    final case object Write extends Permission
    final case object WriteAcp extends Permission
  }

  final case class Grant(grantee: Grantee, permission: Permission)

  object Grant {
    implicit lazy val show: Show[Grant] = Show.fromToString
    implicit lazy val eq: Eq[Grant] = Eq.fromUniversalEquals
  }

  final case class AccessControlList(grants: List[Grant], owner: Owner)

  object AccessControlList {
    implicit lazy val show: Show[AccessControlList] = Show.fromToString
    implicit lazy val eq: Eq[AccessControlList] = Eq.fromUniversalEquals
  }
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

    implicit lazy val show: Show[HTTPMethod] = Show.fromToString
    implicit lazy val eq: Eq[HTTPMethod] = Eq.fromUniversalEquals

    final case object DELETE extends HTTPMethod with Uppercase
    final case object GET extends HTTPMethod with Uppercase
    final case object POST extends HTTPMethod with Uppercase
    final case object PUT extends HTTPMethod with Uppercase
  }

}
