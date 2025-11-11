package s3dsl.domain


import cats.Applicative
import cats.data.NonEmptyMap
import cats.implicits._
import org.scalacheck.Arbitrary
import s3dsl.domain.auth.Domain._
import org.scalacheck.cats.implicits._
import scala.collection.immutable.SortedMap
import org.scalacheck.Gen

@SuppressWarnings(Array("org.wartremover.warts.ExplicitImplicitTypes"))
object Gens {
  private val smallStringSetGen = Gen.buildableOf[Set[String], String](Gen.alphaNumStr).map(_.take(7))
  private lazy val sortedStringStringSetMapGen = for {
    keys <- smallStringSetGen
    kv <- keys.toList.traverse(k =>
      Applicative[Gen].map2(Gen.const(k), smallStringSetGen)((a, b) => (a, b))
    )
  } yield SortedMap(kv:_*)

  private lazy val arbStringGen = Arbitrary.arbString.arbitrary
  implicit lazy val policyVersionArb: Arbitrary[Policy.Version] = Arbitrary(arbStringGen.map(Policy.Version.apply))
  implicit lazy val resourceArb: Arbitrary[Resource] = Arbitrary(arbStringGen.map(Resource.apply))

  implicit lazy val principalProviderArb: Arbitrary[Principal.Provider] = Arbitrary(arbStringGen.map(Principal.Provider.apply))
  implicit lazy val principalIdArb: Arbitrary[Principal.Id] = Arbitrary(arbStringGen.map(Principal.Id.apply))

  implicit lazy val principalArb: Arbitrary[Principal] = Arbitrary(Tuple2.apply(
    arbStringGen,
    arbStringGen
  ).mapN((provider, id) => Principal(Principal.Provider(provider), Principal.Id(id))))

  implicit lazy val principalSetArb: Arbitrary[Set[Principal]] = Arbitrary(
    Gen.listOf(principalArb.arbitrary).map(_.toSet)
  )

  implicit lazy val nemArb: Arbitrary[NonEmptyMap[String, Set[String]]] = Arbitrary(
      sortedStringStringSetMapGen.suchThat(_.nonEmpty).map(m => NonEmptyMap.fromMapUnsafe(m))
  )

  lazy val conditionSetGen: Gen[Set[Condition]] = for {
    types <- smallStringSetGen.map(_.toList)
    list <- types.traverse(s =>
      Applicative[Gen].map2(Gen.const(s), nemArb.arbitrary)((a, b) => Condition(a, b))
    )
  } yield list.toSet

  implicit lazy val conditionArb: Arbitrary[Set[Condition]] = Arbitrary(conditionSetGen)

  implicit lazy val statementWriteArb: Arbitrary[StatementWrite] = Arbitrary(
    Tuple6.apply(
      Arbitrary.arbString.arbitrary,
      Gen.oneOf(Effect.values),
      principalSetArb.arbitrary,
      Gen.someOf(S3Action.values).map(_.toSet),
      Gen.listOf(resourceArb.arbitrary).map(_.toSet),
      conditionSetGen
    ).mapN(StatementWrite.apply))


  implicit lazy val statementReadArb: Arbitrary[StatementRead] = Arbitrary( Tuple6.apply(
      Gen.option(Arbitrary.arbString.arbitrary),
      Gen.oneOf(Effect.values),
      principalSetArb.arbitrary,
      Gen.someOf(S3Action.values).map(_.toSet),
      Gen.listOf(resourceArb.arbitrary).map(_.toSet),
      conditionSetGen
  ).mapN(StatementRead.apply))

  implicit lazy val policyWriteArb: Arbitrary[PolicyWrite] = Arbitrary(Tuple3.apply(
      Gen.option(Arbitrary.arbString.arbitrary),
      policyVersionArb.arbitrary,
      Gen.listOf(statementWriteArb.arbitrary)
  ).mapN(PolicyWrite.apply))

  implicit lazy val policyReadArb: Arbitrary[PolicyRead] = Arbitrary(Tuple3.apply(
      Gen.option(Arbitrary.arbString.arbitrary),
      policyVersionArb.arbitrary,
      Gen.listOf(statementReadArb.arbitrary)
  ).mapN(PolicyRead.apply))


}
