package s3dsl.domain

import cats.Applicative
import io.estatico.newtype.Coercible
import org.scalacheck.{Arbitrary, Gen}
import s3dsl.domain.auth.Domain.Principal
import org.scalacheck.cats.instances.GenInstances._

object Gens {
  implicit def coercibleArb[R, N](implicit ev: Coercible[Arbitrary[R], Arbitrary[N]], R: Arbitrary[R]): Arbitrary[N] = ev(R)

  def principalGen(implicit a: Arbitrary[Principal.Provider], b: Arbitrary[Principal.Id]): Gen[Principal] = Applicative[Gen].map2(
    a.arbitrary, b.arbitrary
  )((a2, b2) => Principal(a2, b2))

  implicit val principalArb: Arbitrary[Principal] = Arbitrary(principalGen)
}
