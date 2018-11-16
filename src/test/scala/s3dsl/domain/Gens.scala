package s3dsl.domain

import io.estatico.newtype.Coercible
import org.scalacheck.Arbitrary

object Gens {
  implicit def coercibleEq[R, N](implicit ev: Coercible[Arbitrary[R], Arbitrary[N]], R: Arbitrary[R]): Arbitrary[N] = ev(R)
}
