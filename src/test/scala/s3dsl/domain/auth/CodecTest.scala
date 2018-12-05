package s3dsl.domain.auth

import java.io.File
import s3dsl.domain.Gens._
import enumeratum.scalacheck.arbEnumEntry
import io.circe.Json
import s3dsl.domain.auth.Domain._
import io.circe.syntax._
import io.circe.literal._
import io.circe.parser.decode
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import scala.io.Source


object CodecTest extends Specification with ScalaCheck {

  "Action codec" should {
    "be reversible" in {
      prop { action: S3Action =>
        action.asJson.asString should beSome(action.entryName)
        action.entryName.asJson.as[S3Action].toOption should beSome(action)
      }
    }
  }

  "Principal.Provider codec" should {
    "be reversible" in {
      prop { provider: Principal.Provider =>
        provider.asJson.asString should beSome(provider.v)
        provider.v.asJson.as[Principal.Provider].toOption should beSome(provider)
      }.set(maxSize = 10)
    }
  }

  "Principal.Id codec" should {
    "be reversible" in {
      prop { id: Principal.Id =>
        id.asJson.asString should beSome(id.v)
        id.v.asJson.as[Principal.Id].toOption should beSome(id)
      }.set(maxSize = 10)
    }
  }

  "Set[Principal] codec" should {
    import Principal._

    "decode an example json" in {
      val json = json"""
          {
            "AWS": [
          	  "arn:aws:iam::ACCOUNT_ID:user/USERNAME_A",
          		"arn:aws:iam::ACCOUNT_ID:user/USERNAME_B"
          		]
          }"""
      json.as[Set[Principal]] should beRight { p: Set[Principal] => p should haveSize(2) }
    }

    "decode string and array values" in {
      val json = json"""{
                    "AWS": "*",
                     "Arr": ["a", "b"]
                     }"""
      json.as[Set[Principal]] should beRight { p: Set[Principal] => p should haveSize(3) }
    }

    "be reversible" in {
      prop {p: Set[Principal] =>
        p.asJson.as[Set[Principal]] should beRight{ p2: Set[Principal] => p2 should containAllOf(p.toList) }
      }.set(maxSize = 20)
    }
  }

  "Effect codec" should {
    "be reversible" in {
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

    "be reversible" in {
      prop {c: Set[Condition] =>
        c.asJson.as[Set[Condition]] should beRight{ c2: Set[Condition] =>
          c2 should containAllOf(c.toList)
        }
      }.set(maxSize = 20)
    }
  }

  "Resource coded" should {
    "be reversible" in {
      prop {r: Resource =>
        r.asJson.as[Resource] should beRight{ r2: Resource =>
          r2 should be_==(r)
        }
      }.set(maxSize = 10)
    }
  }

  "StatementWrite codec" should {
    "be reversible" in {
      prop {s: StatementWrite =>
        s.asJson.as[StatementWrite] should beRight{ s2: StatementWrite => s2 should be_==(s) }
      }.set(maxSize = 20)
    }
  }

  "StatementRead codec" should {

    "be reversible" in {
      prop {s: StatementRead =>
        s.asJson.as[StatementRead] should beRight{ s2: StatementRead => s2 should be_==(s) }
      }.set(maxSize = 20)
    }

    "decode a simple example" in {
      val json = json"""{
                  "Action": [
                    "s3:GetObject"
                  ],
                  "Effect": "Allow",
                  "Principal": {
                    "AWS": [
                      "*"
                    ]
                  },
                  "Resource": [
                    "arn:aws:s3:::BUCKET_NAME/*"
                  ]
                }"""

      json.as[StatementRead] should beRight
    }
  }

  "Version codec" should {
    "be reversible" in {
      prop {v: Policy.Version =>
        v.asJson.as[Policy.Version] should beRight{ v2: Policy.Version => v2 should be_==(v) }
      }.set(maxSize = 20)
    }
  }

  "PolicyWrite encoder" should {
    "be successful" in {
      prop {p: PolicyWrite =>
        p.asJson should be_!=(Json.Null)
      }.set(maxSize = 20)
    }
  }

  "PolicyRead decoder" should {

    "decode a simple example" in {
      val json = json"""{
                         "Version": "2012-10-17",
                         "Statement": [
                           {
                             "Action": [
                               "s3:GetObject"
                             ],
                             "Effect": "Allow",
                             "Principal": {
                               "AWS": [
                                 "*"
                               ]
                             },
                             "Resource": [
                               "arn:aws:s3:::BUCKET_NAME/*"
                             ]
                           }
                         ]
                       }"""

      json.as[PolicyRead] should beRight
    }

    "be able to decode a set of examples" in {
      loadFiles("src/test/resources/authpolicies/", "json").map { s =>
        io.circe.parser.parse(s).map(_.as[PolicyRead]) should beRight
      }
    }
  }

  def loadFiles(path: String, ext: String) = new File(path)
    .listFiles.toList
    .filter(_.getName.endsWith(s".$ext"))
    .map(f => Source.fromFile(f).mkString)

}
