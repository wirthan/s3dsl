package s3dsl

//import scalaz.scalacheck.ScalaCheckBinding.GenMonad
import eu.timepit.refined.cats.syntax._
import org.scalacheck.{Arbitrary, Gen}
import s3dsl.domain.S3._


object Gens {

  val blobGen: Gen[String] = sizeLimitedString(512, 2048, Gen.alphaNumChar)

  val keyGen: Gen[Key] = sizeLimitedString(10, 30, Gen.alphaLowerChar).map(s =>
    Key.validate(s).fold(_ => sys.error("error generating Key"), identity)
  )
  implicit val keyArb = Arbitrary(keyGen)

  private def sizeLimitedString(minimum: Int, maximum: Int, gen: Gen[Char]): Gen[String] =
    Gen.chooseNum(minimum, maximum).flatMap { n =>
      Gen.sequence[String, Char](List.fill(n)(gen)).map { s =>
        val newS =
          if(s.length > maximum) s.drop(s.length - maximum)
          else s
        newS ++ List.fill(s.length - newS.length)('X')
      }
    }

}
