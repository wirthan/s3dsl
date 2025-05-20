package s3dsl.domain


import cats.Applicative
import cats.data.NonEmptyMap
import cats.implicits._
import org.scalacheck.Arbitrary
import s3dsl.domain.auth.Domain._
import org.scalacheck.cats.implicits._
import scala.collection.immutable.SortedMap
import org.scalacheck.Gen
import org.scalacheck.ScalacheckShapeless._

@SuppressWarnings(Array("org.wartremover.warts.ExplicitImplicitTypes"))
object Gens {
  private val smallStringSetGen = Gen.buildableOf[Set[String], String](Gen.alphaNumStr).map(_.take(7))
  private lazy val sortedStringStringSetMapGen = for {
    keys <- smallStringSetGen
    kv <- keys.toList.traverse(k =>
      Applicative[Gen].map2(Gen.const(k), smallStringSetGen)((a, b) => (a, b))
    )
  } yield SortedMap(kv:_*)

  implicit lazy val policyVersionArb = implicitly[Arbitrary[Policy.Version]]
  implicit lazy val resourceArb = implicitly[Arbitrary[Resource]]

  implicit lazy val principalProviderArb = implicitly[Arbitrary[Principal.Provider]]
  implicit lazy val principalIdArb = implicitly[Arbitrary[Principal.Id]]
  implicit lazy val principalArb = implicitly[Arbitrary[Set[Principal]]]

  implicit lazy val nemArb: Arbitrary[NonEmptyMap[String, Set[String]]] = Arbitrary(
      sortedStringStringSetMapGen.suchThat(_.nonEmpty).map(m => NonEmptyMap.fromMapUnsafe(m))
  )

  lazy val conditionGen = for {
    types <- smallStringSetGen.map(_.toList)
    list <- types.traverse(s =>
      Applicative[Gen].map2(Gen.const(s), nemArb.arbitrary)((a, b) => Condition(a, b))
    )
  } yield list.toSet

  implicit lazy val conditionArb = Arbitrary(conditionGen)

  implicit lazy val statementWriteArb = implicitly[Arbitrary[StatementWrite]]

  implicit lazy val statementReadArb = implicitly[Arbitrary[StatementRead]]

  implicit lazy val policyWriteArb = implicitly[Arbitrary[PolicyWrite]]

  implicit lazy val policyReadArb = implicitly[Arbitrary[PolicyRead]]

}
