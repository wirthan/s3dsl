package s3dsl.domain.auth

import java.io.File

import s3dsl.domain.Gens._
import enumeratum.scalacheck.arbEnumEntry
import io.circe.Json
import s3dsl.domain.auth.Domain._
import io.circe.syntax._
import io.circe.parser.decode
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

import scala.io.Source

// TODO: Some of the json values can either be a String or an Array
// Examples: https://gist.github.com/magnetikonline/6215d9e80021c1f8de12
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
        provider.asJson.asString should beSome(provider.v)
        provider.v.asJson.as[Principal.Provider].toOption should beSome(provider)
      }.set(maxSize = 10)
    }
  }

  "Principal.Id codec" should {
    "be correct" in {
      prop { id: Principal.Id =>
        id.asJson.asString should beSome(id.v)
        id.v.asJson.as[Principal.Id].toOption should beSome(id)
      }.set(maxSize = 10)
    }
  }

  "Set[Principal] codec" should {
    import Principal._
    val exampleJson = """
          {
            "AWS": [
          	  "arn:aws:iam::ACCOUNT_ID:user/USERNAME_A",
          		"arn:aws:iam::ACCOUNT_ID:user/USERNAME_B"
          		]
          }"""

    "decode an example json" in {
      decode[Set[Principal]](exampleJson) should beRight { p: Set[Principal] =>
        p should haveSize(2)
      }
    }

    "be correct" in {
      prop {p: Set[Principal] =>
        p.asJson.as[Set[Principal]] should beRight{ p2: Set[Principal] =>
          p2 should containAllOf(p.toList)
        }
      }.set(maxSize = 20)
    }
  }

  "Effect codec" should {
    "be correct" in {
      val (allow, deny): (Effect, Effect) = (Effect.Allow, Effect.Deny)

      allow.asJson.asString should beSome("Allow")
      deny.asJson.asString should beSome("Deny")
      allow.asJson.as[Effect] should beRight(Effect.Allow)
      deny.asJson.as[Effect] should beRight(Effect.Deny)
    }
  }

  "Condition codec" should {
    "be correct for an example json" in {
      val exampleJson = """
        {
          "StringLike": {
            "aws:Referer": [
              "http://domain.com/*",
              "http://www.domain.com/*"
            ]
          }
        }"""

      decode[Set[Condition]](exampleJson) should beRight { p: Set[Condition] =>
        p should haveSize(1)
      }
    }

    "be correct" in {
      prop {c: Set[Condition] =>
        c.asJson.as[Set[Condition]] should beRight{ c2: Set[Condition] =>
          c2 should containAllOf(c.toList)
        }
      }.set(maxSize = 20)
    }
  }

  "Resource coded" should {
    "be correct" in {
      prop {r: Resource =>
        r.asJson.as[Resource] should beRight{ r2: Resource =>
          r2 should be_==(r)
        }
      }.set(maxSize = 10)
    }
  }

  "Statement codec" should {
    "be correct" in {
      prop {s: Statement =>
        s.asJson.as[Statement] should beRight{ s2: Statement =>
          s2 should be_==(s)
        }
      }.set(maxSize = 20)
    }
  }

  "Policy codec" should {
    "be correct" in {
      prop {p: Policy =>
        p.asJson.as[Policy] should beRight{ p2: Policy =>
          p2 should be_==(p)
        }
      }.set(maxSize = 20)
    }

    "be able to decode a set of examples" in {
      loadFiles("src/test/resources/authpolicies/", "json").map(s =>
        Json.fromString(s).as[Policy] should beRight
      )
    }
  }

  def loadFiles(path: String, ext: String) = new File(path)
    .listFiles
    .filter(_.getName.endsWith(s".$ext"))
    .map(f => Source.fromFile(f).mkString)
    .toList

}
