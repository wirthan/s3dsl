package s3dsl.domain.auth

import cats.data.NonEmptyMap
import cats.{Eq, Order}
import cats.implicits._
import mouse.boolean._
import enumeratum.{Enum, EnumEntry}
import io.circe._
import io.circe.syntax._
import io.estatico.newtype.macros.newtype

// TODO: Improve typesafety
@SuppressWarnings(Array(
  "org.wartremover.warts.ExplicitImplicitTypes",
  "org.wartremover.warts.ImplicitConversion",
  "org.wartremover.warts.ImplicitParameter"))
object Domain {
  // "Principal" has permission to do "Action" to "Resource" where "Condition" applies.

  private implicit def decodeValueOrArray[T](implicit tDec: Decoder[T]): Decoder[List[T]] = (c: HCursor) =>
    c.value.isArray.fold(c.value, Json.arr(c.value)).as[List[T]](Decoder.decodeList[T])

  //
  // Policy
  //

  sealed trait Policy
  object Policy {

    @newtype final case class Version(value: String)
    object Version {
      lazy val defaultVersion: Version = Version("2012-10-17")
      implicit lazy val eq: Eq[Version] = deriving
      implicit lazy val encoder: Encoder[Version] = deriving
      implicit lazy val decoder: Decoder[Version] = deriving
    }
  }

  final case class PolicyWrite(id: Option[String],
                               version: Policy.Version,
                               statements: List[StatementWrite]) extends Policy

  object PolicyWrite {
    implicit lazy val eq: Eq[PolicyWrite] = Eq.fromUniversalEquals[PolicyWrite]

    implicit lazy val encoder: Encoder[PolicyWrite] =
      Encoder.forProduct3("Id", "Version", "Statement")(p => (p.id, p.version, p.statements))

  }

  final case class PolicyRead(id: Option[String],
                              version: Policy.Version,
                              statements: List[StatementRead]) extends Policy

  object PolicyRead {
    implicit lazy val eq: Eq[PolicyRead] = Eq.fromUniversalEquals[PolicyRead]

    implicit lazy val decoder: Decoder[PolicyRead] = (c: HCursor) => for {
      id <- c.downField("Id").success.flatTraverse(_.as[Option[String]])
      version <- c.downField("Version").as[Policy.Version]
      statements <- c.downField("Statement").as[List[StatementRead]]
    } yield PolicyRead(id, version, statements)

  }


  //
  // Statement
  //

  final case class StatementWrite(id: String,
                                  effect: Effect,
                                  principals: Set[Principal],
                                  actions: Set[S3Action],
                                  resources: Set[Resource],
                                  conditions: Set[Condition])

  object StatementWrite {
    implicit lazy val eq: Eq[StatementWrite] = Eq.fromUniversalEquals[StatementWrite]

    implicit lazy val order: Order[StatementWrite] = Order.by(_.id)

    implicit lazy val encoder: Encoder[StatementWrite] =
      Encoder.forProduct6("Sid", "Effect", "Principal", "Action", "Resource", "Condition")(s =>
        (s.id, s.effect, s.principals, s.actions, s.resources, s.conditions))

    private[s3dsl] implicit lazy val decoder: Decoder[StatementWrite] =
      Decoder.forProduct6("Sid", "Effect", "Principal", "Action", "Resource", "Condition")(StatementWrite.apply)
  }

  final case class StatementRead(id: Option[String],
                                 effect: Effect,
                                 principals: Set[Principal],
                                 actions: Set[S3Action],
                                 resources: Set[Resource],
                                 conditions: Set[Condition])

  object StatementRead {
    implicit lazy val eq: Eq[StatementRead] = Eq.fromUniversalEquals[StatementRead]

    private[s3dsl] implicit lazy val encoder: Encoder[StatementRead] =
      Encoder.forProduct6("Sid", "Effect", "Principal", "Action", "Resource", "Condition")(s =>
        (s.id, s.effect, s.principals, s.actions, s.resources, s.conditions))

    implicit lazy val decoder: Decoder[StatementRead] = (c: HCursor) => for {
      id <- c.downField("Sid").success.flatTraverse(_.as[Option[String]])
      effect <- c.downField("Effect").as[Effect]
      principals <- decodeSet[Principal](c, "Principal")
      actions <- decodeSet[S3Action](c, "Action")
      resources <- decodeSet[Resource](c, "Resource")
      conditions <- decodeSet[Condition](c, "Condition")
    } yield StatementRead(id, effect, principals, actions, resources, conditions)
  }

  //
  // Resource
  //

  @newtype final case class Resource(v: String)
  object Resource {
    implicit lazy val order: Order[Resource] = deriving
    implicit lazy val encoder: Encoder[Resource] = deriving
    implicit lazy val decoder: Decoder[Resource] = deriving
  }

  //
  // Condition
  //

  final case class Condition(kind: String, condition: NonEmptyMap[String, Set[String]])
  object Condition {
    implicit lazy val eq: Eq[Condition] = Eq.fromUniversalEquals[Condition]

    private val mapMapEncoder = implicitly[Encoder[Map[String, Map[String, Set[String]]]]]
    implicit lazy val setEncoder: Encoder[Set[Condition]] = Encoder.instance { set =>
      val map = set.map(c => c.kind -> (c.condition.toSortedMap: Map[String, Set[String]]).map(cc => (cc._1, cc._2)))
      map.toMap.asJson(mapMapEncoder): Json
    }

    private val mapMapDecoder = implicitly[Decoder[Map[String, NonEmptyMap[String, Set[String]]]]]
    implicit lazy val setDecoder: Decoder[Set[Condition]] = mapMapDecoder.map(m =>
      m.map(t => Condition(t._1, t._2)).toSet
    )

  }

  //
  // Effect
  //

  sealed trait Effect extends EnumEntry {
    def fold[X](allow: => X, deny: => X): X = this match {
      case Effect.Allow => allow
      case Effect.Deny  => deny
    }
  }
  object Effect extends Enum[Effect] {
    implicit lazy val eq: Order[Effect] = Order.by[Effect, String](_.entryName)
    implicit lazy val encoder: Encoder[Effect] = enumeratum.Circe.encoder(Effect)
    implicit lazy val decoder: Decoder[Effect] = enumeratum.Circe.decoder(Effect)

    lazy val values = findValues

    final case object Allow extends Effect
    final case object Deny extends Effect
  }


  //
  // Principal
  //

  final case class Principal(provider: Principal.Provider, id: Principal.Id)

  object Principal {
    implicit lazy val eq: Eq[Principal] = Eq.fromUniversalEquals[Principal]
    implicit lazy val order: Order[Principal] = Order.by[Principal, (Provider, Id)](p => (p.provider, p.id))

    private val mapEncoder = implicitly[Encoder[Map[String, List[String]]]]
    implicit lazy val setEncoder: Encoder[Set[Principal]] = Encoder.instance { set =>
      val map = set.map(p => p.provider.v -> p.id.v).groupBy(_._1).mapValues(_.map(_._2).toList)
      map.asJson(mapEncoder): Json
    }

    private val mapDecoder = implicitly[Decoder[Map[String, List[String]]]]
    implicit lazy val setDecoder: Decoder[Set[Principal]] = mapDecoder.map(m =>
      m.flatMap(t =>
        t._2.map(i => Principal(Provider(t._1), Id(i)))
      ).toSet
    )

    @newtype final case class Id(v: String)
    object Id {
      implicit lazy val order: Order[Id] = deriving
      implicit lazy val encoder: Encoder[Id] = deriving
      implicit lazy val decoder: Decoder[Id] = deriving
      lazy val all: Id = Id("*")
    }

    @newtype final case class Provider(v: String)
    object Provider {
      implicit lazy val order: Order[Provider] = deriving
      implicit lazy val encoder: Encoder[Provider] = deriving
      implicit lazy val decoder: Decoder[Provider] = deriving
      lazy val all: Provider = Provider("*")
      lazy val aws: Provider = Provider("AWS")
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

  private def decodeSet[A](c: HCursor, field: String)(implicit ev: Decoder[Set[A]]): Decoder.Result[Set[A]] =
    c.downField(field).success.toList.flatTraverse(_.as[Set[A]].map(_.toList)).map(_.toSet)
}
