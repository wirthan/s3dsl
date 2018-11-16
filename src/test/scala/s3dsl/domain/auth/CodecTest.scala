package s3dsl.domain.auth

import s3dsl.domain.Gens._
import enumeratum.scalacheck.arbEnumEntry
import s3dsl.domain.auth.Domain._
import io.circe.syntax._
import io.circe.parser.decode
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

object CodecTest extends Specification with ScalaCheck {

  "Action codec" should {
    "be correct" in {
      prop { action: S3Action =>
        action.asJson.asString should beSome(action.entryName)
        action.entryName.asJson.as[S3Action].toOption should beSome(action)
      }
    }
  }

  "Principal.Provider codec" should {
    "be correct" in {
      prop { provider: Principal.Provider =>
        provider.asJson.asString should beSome(provider.entryName)
        provider.entryName.asJson.as[Principal.Provider].toOption should beSome(provider)
      }
    }
  }

  "Principal.Id codec" should {
    "be correct" in {
      prop { id: Principal.Id =>
        id.asJson.asString should beSome(id.value)
        id.value.asJson.as[Principal.Id].toOption should beSome(id)
      }
    }
  }

  "Set[Principal] codec" should {

    "decode an example" in {
      val principals = decode[Set[Principal]]("""
          {
            "AWS": [
          	  "arn:aws:iam::ACCOUNT_ID:user/USERNAME_A",
          		"arn:aws:iam::ACCOUNT_ID:user/USERNAME_B"
          		]
          }
        """)
      println(principals)
      principals should beRight[Set[Principal] ]

    }

  }

}
