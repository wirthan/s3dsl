package s3dsl.domain.auth

import cats.Order
import cats.implicits._
import enumeratum.{Enum, EnumEntry}
import io.circe._
import io.circe.generic.semiauto._
import io.estatico.newtype.macros.newtype

@SuppressWarnings(Array(
  "org.wartremover.warts.ExplicitImplicitTypes",
  "org.wartremover.warts.ImplicitConversion",
  "org.wartremover.warts.ImplicitParameter"))
object Domain {
  // "Principal" has permission to do "Action" to "Resource" where "Condition" applies.

  final case class Policy(id: String, version: Policy.Version, statements: List[Statement])

  object Policy {
    @newtype final case class Version(value: String)

    lazy val defaultVersion: Version = Version("2012-10-17")
  }


  // TODO: Some of the json values can either be a String or an Array
  // https://gist.github.com/magnetikonline/6215d9e80021c1f8de12
  final case class Statement(id: String,
                             effect: Effect,
                             principals: Set[Principal],
                             actions: Set[S3Action],
                             resources: Set[Resource],
                             conditions: Set[Condition])

  @newtype final case class Resource(value: String)

  final case class Condition(kind: String, conditionKey: String, values: List[String])

  //
  // Effect
  //

  sealed trait Effect {
    def fold[X](allow: => X, deny: => X): X = this match {
      case Allow => allow
      case Deny  => deny
    }
  }
  final case object Allow extends Effect
  final case object Deny extends Effect

  //
  // Principal
  //

  final case class Principal(provider: Principal.Provider, id: Principal.Id)

  object Principal{
    implicit lazy val setEncoder: Encoder[Set[Principal]] = deriveEncoder[List[(Provider, List[Id])]]
      .contramap[Set[Principal]](s => s.toList.groupBy(_.provider).mapValues(_.map(_.id)).toList)

    implicit lazy val setDecoder: Decoder[Set[Principal]] = deriveDecoder[List[(Provider, List[Id])]]
      .map(_.flatMap(t => t._2.map(Principal(t._1, _))).toSet)

    @newtype final case class Id(value: String)
    object Id {
      implicit lazy val order: Order[Id] = deriving
      implicit lazy val encoder: Encoder[Id] = deriving
      implicit lazy val decoder: Decoder[Id] = deriving
      lazy val all: Id = Id("*")
    }

    sealed abstract class Provider(override val entryName: String) extends EnumEntry {
      def fold[X](aws: => X, all: => X): X = this match {
        case Provider.AWS => aws
        case Provider.All => all
      }
    }
    object Provider extends Enum[Provider] {
      implicit lazy val order: Order[Provider] = Order.by(_.entryName)
      implicit lazy val encoder: Encoder[Provider] = enumeratum.Circe.encoder(Provider)
      implicit lazy val decoder: Decoder[Provider] = enumeratum.Circe.decoder(Provider)

      lazy val values = findValues

      final case object AWS extends Provider("AWS")
      final case object All extends Provider("*")
    }
  }


  //
  // Action
  //

  sealed abstract class S3Action(override val entryName: String) extends EnumEntry

  object S3Action extends Enum[S3Action] {
    implicit lazy val order: Order[S3Action] = Order.by(_.entryName)
    implicit lazy val encoder: Encoder[S3Action] = enumeratum.Circe.encoder(S3Action)
    implicit lazy val decoder: Decoder[S3Action] = enumeratum.Circe.decoder(S3Action)

    lazy val values = findValues

    final case object All extends S3Action("s3:*")
    final case object GetObject extends S3Action("s3:GetObject")
    final case object GetObjectVersion extends S3Action("s3:GetObjectVersion")
    final case object PutObject extends S3Action("s3:PutObject")
    final case object GetObjectAcl extends S3Action("s3:GetObjectAcl")
    final case object GetObjectVersionAcl extends S3Action("s3:GetObjectVersionAcl")
    final case object SetObjectAcl extends S3Action("s3:PutObjectAcl")
    final case object SetObjectVersionAcl extends S3Action("s3:PutObjectAclVersion")
    final case object DeleteObject extends S3Action("s3:DeleteObject")
    final case object DeleteObjectVersion extends S3Action("s3:DeleteObjectVersion")
    final case object ListMultipartUploadParts extends S3Action("s3:ListMultipartUploadParts")
    final case object AbortMultipartUpload extends S3Action("s3:AbortMultipartUpload")
    final case object RestoreObject extends S3Action("s3:RestoreObject")
    final case object CreateBucket extends S3Action("s3:CreateBucket")
    final case object DeleteBucket extends S3Action("s3:DeleteBucket")
    final case object ListObjects extends S3Action("s3:ListBucket")
    final case object ListObjectVersions extends S3Action("s3:ListBucketVersions")
    final case object ListBuckets extends S3Action("s3:ListAllMyBuckets")
    final case object ListBucketMultipartUploads extends S3Action("s3:ListBucketMultipartUploads")
    final case object GetBucketAcl extends S3Action("s3:GetBucketAcl")
    final case object SetBucketAcl extends S3Action("s3:PutBucketAcl")
    final case object GetBucketCrossOriginConfiguration extends S3Action("s3:GetBucketCORS")
    final case object SetBucketCrossOriginConfiguration extends S3Action("s3:PutBucketCORS")
    final case object GetBucketVersioningConfiguration extends S3Action("s3:GetBucketVersioning")
    final case object SetBucketVersioningConfiguration extends S3Action("s3:PutBucketVersioning")
    final case object GetBucketRequesterPays extends S3Action("s3:GetBucketRequestPayment")
    final case object SetBucketRequesterPays extends S3Action("s3:PutBucketRequestPayment")
    final case object GetBucketLocation extends S3Action("s3:GetBucketLocation")
    final case object GetBucketPolicy extends S3Action("s3:GetBucketPolicy")
    final case object SetBucketPolicy extends S3Action("s3:PutBucketPolicy")
    final case object DeleteBucketPolicy extends S3Action("s3:DeleteBucketPolicy")
    final case object GetBucketNotificationConfiguration extends S3Action("s3:GetBucketNotification")
    final case object SetBucketNotificationConfiguration extends S3Action("s3:PutBucketNotification")
    final case object GetBucketLogging extends S3Action("s3:GetBucketLogging")
    final case object SetBucketLogging extends S3Action("s3:PutBucketLogging")
    final case object GetBucketTagging extends S3Action("s3:GetBucketTagging")
    final case object SetBucketTagging extends S3Action("s3:PutBucketTagging")
    final case object GetBucketWebsiteConfiguration extends S3Action("s3:GetBucketWebsite")
    final case object SetBucketWebsiteConfiguration extends S3Action("s3:PutBucketWebsite")
    final case object DeleteBucketWebsiteConfiguration extends S3Action("s3:DeleteBucketWebsite")
    final case object GetBucketLifecycleConfiguration extends S3Action("s3:GetLifecycleConfiguration")
    final case object SetBucketLifecycleConfiguration extends S3Action("s3:PutLifecycleConfiguration")
  }
}
