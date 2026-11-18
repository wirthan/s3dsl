package s3dsl.domain.auth

import cats.data.NonEmptyMap
import cats.implicits._
import cats.{Eq, Order}
import enumeratum.{Enum, EnumEntry}
import io.circe._
import io.circe.syntax._
import mouse.boolean._

@SuppressWarnings(Array(
  "org.wartremover.warts.ExplicitImplicitTypes",
  "org.wartremover.warts.ImplicitConversion",
  "org.wartremover.warts.ImplicitParameter"))
object Domain {
  // "Principal" has permission to do "Action" to "Resource" where "Condition" applies.

  private implicit def decodeValueOrArray[T](implicit ev: Decoder[T]): Decoder[List[T]] = (c: HCursor) =>
    c.value.isArray.fold(c.value, Json.arr(c.value)).as[List[T]](using Decoder.decodeList[T])

  //
  // Policy
  //

  sealed trait Policy
  object Policy {

    final case class Version(value: String) extends AnyVal
    object Version {
      lazy val defaultVersion: Version = Version("2012-10-17")
      implicit lazy val eq: Eq[Version] = Eq.fromUniversalEquals
      implicit lazy val encoder: Encoder[Version] = Encoder[String].contramap(_.value)
      implicit lazy val decoder: Decoder[Version] = Decoder[String].map(Version.apply)
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

    implicit lazy val encoder: Encoder[StatementWrite] = Encoder.instance[StatementWrite]{s =>
      val encoder: Encoder[StatementWrite] = s.conditions.isEmpty.fold(
        Encoder.forProduct5("Sid", "Effect", "Principal", "Action", "Resource")(s =>
          (s.id, s.effect, s.principals, s.actions, s.resources)
        ),
        Encoder.forProduct6("Sid", "Effect", "Principal", "Action", "Resource", "Condition")(s =>
          (s.id, s.effect, s.principals, s.actions, s.resources, s.conditions)
        )
      )
      s.asJson(using encoder)
    }

    private[s3dsl] implicit lazy val decoder: Decoder[StatementWrite] = (c: HCursor) => for {
      id <- c.downField("Sid").as[String]
      effect <- c.downField("Effect").as[Effect]
      principals <- decodeSet[Principal](c, "Principal")
      actions <- decodeSet[S3Action](c, "Action")
      resources <- decodeSet[Resource](c, "Resource")
      conditions <- decodeSet[Condition](c, "Condition")
    } yield StatementWrite(id, effect, principals, actions, resources, conditions)

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

  final case class Resource(v: String) extends AnyVal
  object Resource {
    implicit lazy val order: Order[Resource] = Order[String].contramap(_.v)
    implicit lazy val encoder: Encoder[Resource] = Encoder[String].contramap(_.v)
    implicit lazy val decoder: Decoder[Resource] = Decoder[String].map(Resource.apply)
  }

  //
  // Condition
  //

  final case class Condition(kind: String, condition: NonEmptyMap[String, Set[String]])
  object Condition {
    implicit lazy val eq: Eq[Condition] = Eq.fromUniversalEquals[Condition]

    private val mapMapEncoder = Encoder[Map[String, Map[String, Set[String]]]]
    implicit lazy val setEncoder: Encoder[Set[Condition]] = Encoder.instance { set =>
      val map = set.map(c => c.kind -> (c.condition.toSortedMap: Map[String, Set[String]]).map(cc => (cc._1, cc._2)))
      map.toMap.asJson(using mapMapEncoder): Json
    }

    private val mapMapDecoder = Decoder[Map[String, NonEmptyMap[String, Set[String]]]]
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

    case object Allow extends Effect
    case object Deny extends Effect
  }


  //
  // Principal
  //

  final case class Principal(provider: Principal.Provider, id: Principal.Id)

  object Principal {
    implicit lazy val eq: Eq[Principal] = Eq.fromUniversalEquals[Principal]
    implicit lazy val order: Order[Principal] = Order.by[Principal, (Provider, Id)](p => (p.provider, p.id))

    private val mapEncoder = Encoder[Map[String, List[String]]]
    implicit lazy val setEncoder: Encoder[Set[Principal]] = Encoder.instance { set =>
      val map = set
        .map(p => p.provider.v -> p.id.v)
        .groupBy(_._1)
        .view.mapValues(_.map(_._2).toList).toMap
      map.asJson(using mapEncoder): Json
    }

    private val mapDecoder = Decoder[Map[String, List[String]]]
    implicit lazy val setDecoder: Decoder[Set[Principal]] = mapDecoder.map(m =>
      m.flatMap(t =>
        t._2.map(i => Principal(Provider(t._1), Id(i)))
      ).toSet
    )

    final case class Id(v: String) extends AnyVal
    object Id {
      implicit lazy val order: Order[Id] = Order[String].contramap(_.v)
      implicit lazy val encoder: Encoder[Id] = Encoder[String].contramap(_.v)
      implicit lazy val decoder: Decoder[Id] = Decoder[String].map(Id.apply)
      lazy val all: Id = Id("*")
    }

    final case class Provider(v: String) extends AnyVal
    object Provider {
      implicit lazy val order: Order[Provider] = Order[String].contramap(_.v)
      implicit lazy val encoder: Encoder[Provider] = Encoder[String].contramap(_.v)
      implicit lazy val decoder: Decoder[Provider] = Decoder[String].map(Provider.apply)
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

    case object All extends S3Action("s3:*")
    case object GetObject extends S3Action("s3:GetObject")
    case object GetObjectVersion extends S3Action("s3:GetObjectVersion")
    case object PutObject extends S3Action("s3:PutObject")
    case object GetObjectAcl extends S3Action("s3:GetObjectAcl")
    case object GetObjectVersionAcl extends S3Action("s3:GetObjectVersionAcl")
    case object SetObjectAcl extends S3Action("s3:PutObjectAcl")
    case object SetObjectVersionAcl extends S3Action("s3:PutObjectAclVersion")
    case object DeleteObject extends S3Action("s3:DeleteObject")
    case object DeleteObjectVersion extends S3Action("s3:DeleteObjectVersion")
    case object ListMultipartUploadParts extends S3Action("s3:ListMultipartUploadParts")
    case object AbortMultipartUpload extends S3Action("s3:AbortMultipartUpload")
    case object RestoreObject extends S3Action("s3:RestoreObject")
    case object CreateBucket extends S3Action("s3:CreateBucket")
    case object DeleteBucket extends S3Action("s3:DeleteBucket")
    case object ListObjects extends S3Action("s3:ListBucket")
    case object ListObjectVersions extends S3Action("s3:ListBucketVersions")
    case object ListBuckets extends S3Action("s3:ListAllMyBuckets")
    case object ListBucketMultipartUploads extends S3Action("s3:ListBucketMultipartUploads")
    case object GetBucketAcl extends S3Action("s3:GetBucketAcl")
    case object SetBucketAcl extends S3Action("s3:PutBucketAcl")
    case object GetBucketCrossOriginConfiguration extends S3Action("s3:GetBucketCORS")
    case object SetBucketCrossOriginConfiguration extends S3Action("s3:PutBucketCORS")
    case object GetBucketVersioningConfiguration extends S3Action("s3:GetBucketVersioning")
    case object SetBucketVersioningConfiguration extends S3Action("s3:PutBucketVersioning")
    case object GetBucketRequesterPays extends S3Action("s3:GetBucketRequestPayment")
    case object SetBucketRequesterPays extends S3Action("s3:PutBucketRequestPayment")
    case object GetBucketLocation extends S3Action("s3:GetBucketLocation")
    case object GetBucketPolicy extends S3Action("s3:GetBucketPolicy")
    case object SetBucketPolicy extends S3Action("s3:PutBucketPolicy")
    case object DeleteBucketPolicy extends S3Action("s3:DeleteBucketPolicy")
    case object GetBucketNotificationConfiguration extends S3Action("s3:GetBucketNotification")
    case object SetBucketNotificationConfiguration extends S3Action("s3:PutBucketNotification")
    case object GetBucketLogging extends S3Action("s3:GetBucketLogging")
    case object SetBucketLogging extends S3Action("s3:PutBucketLogging")
    case object GetBucketTagging extends S3Action("s3:GetBucketTagging")
    case object SetBucketTagging extends S3Action("s3:PutBucketTagging")
    case object GetBucketWebsiteConfiguration extends S3Action("s3:GetBucketWebsite")
    case object SetBucketWebsiteConfiguration extends S3Action("s3:PutBucketWebsite")
    case object DeleteBucketWebsiteConfiguration extends S3Action("s3:DeleteBucketWebsite")
    case object GetBucketLifecycleConfiguration extends S3Action("s3:GetLifecycleConfiguration")
    case object SetBucketLifecycleConfiguration extends S3Action("s3:PutLifecycleConfiguration")
  }

  private def decodeSet[A](c: HCursor, field: String)(implicit ev: Decoder[Set[A]]): Decoder.Result[Set[A]] =
    c.downField(field).success.toList.flatTraverse(_.as[Set[A]].map(_.toList)).map(_.toSet)
}
