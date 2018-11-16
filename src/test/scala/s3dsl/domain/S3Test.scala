package s3dsl.domain

import org.specs2.mutable.Specification
import eu.timepit.refined.cats.syntax._
import org.specs2.ScalaCheck
import s3dsl.domain.S3._
import org.specs2.matcher.ValidatedMatchers

object S3Test extends Specification with ValidatedMatchers with ScalaCheck {

  "Validation of BucketName" should {

    "succeed for a 3 character string" in {
      BucketName.validate("a" * 3) should beValid
    }

    "succeed if string starts with a number" in {
      BucketName.validate("1aa") should beValid
    }

    "fail for a 3 character uppercase string" in {
      BucketName.validate("A" * 3) should beInvalid
    }

    "fail for a string containing _" in {
      BucketName.validate("aa_bb") should beInvalid
    }

    "fail for a 2 character String" in {
      BucketName.validate("a" * 2) should beInvalid
    }

    "succeed for a 63 character string" in {
      BucketName.validate("a" * 63) should beValid
    }

    "fail for a 64 character string" in {
      BucketName.validate("a" * 64) should beInvalid
    }

    "fail for abcde fg" in {
      BucketName.validate("abcde fg") should beInvalid
    }

    "fail for 127.0.0.1" in {
      BucketName.validate("a 127.0.0.1") should beInvalid
    }


    "Validation of S3Key" should {

      "succeed for arb string" in {
        prop { (key: String) =>
          Key.validate(key) should beValid
        }
      }

      "succeed for string containing *" in {
        Key.validate("abc/*/def/*") should beValid
      }

      "succeed for a string of size 1024" in {
        Key.validate("a" * 1024) should beValid
      }

      "fail for a string of size 1024 with a trailing /" in {
        Key.validate(s"${"a" * 1023}/") should beInvalid
      }

      "fail for a string > 1024" in {
        Key.validate("a" * 1025) should beInvalid
      }

      "fail for a key starting with a /" in {
        Key.validate("/abababab") should beInvalid
      }

    }

    "toString of Path" should {
      "look nice" in {
        Path(BucketName("testtest"), Key("blob/abc.txt")).toString should_==("testtest/blob/abc.txt")
      }
    }

  }

}
