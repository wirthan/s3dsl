package s3dsl

import java.time.{Month, Year, ZoneId, ZonedDateTime}

import cats.Applicative
import org.scalacheck.cats.instances.GenInstances._

import eu.timepit.refined.cats.syntax._
import org.scalacheck.{Arbitrary, Gen}
import s3dsl.domain.S3._
import scala.jdk.CollectionConverters._

object Gens {

  val blobGen: Gen[String] = sizeLimitedString(512, 2048, Gen.alphaNumChar)

  val bucketNameGen: Gen[BucketName] = sizeLimitedString(3, 63, Gen.alphaLowerChar).map(s =>
    BucketName.validate(s).fold(_ => sys.error("error generating BucketName"), identity)
  )

  val keyGen: Gen[Key] = sizeLimitedString(10, 30, Gen.alphaLowerChar).map(s =>
    Key.validate(s).fold(_ => sys.error("error generating Key"), identity)
  )

  val zonedDateTimeGen = for {
    (year, month) <- Applicative[Gen].tuple2(Gen.choose(-292278994, 292278994), Gen.choose(1, 12))
    dateTime <- Applicative[Gen].map6(
      Gen.choose(1, Month.of(month).length(Year.of(year).isLeap)), //dayOfMonth
      Gen.choose(0, 23), // hour
      Gen.choose(0, 59), // minute
      Gen.choose(0, 59), // second
      Gen.choose(0, 999999999), // nanoOfSecond
      Gen.oneOf(ZoneId.getAvailableZoneIds.asScala.toList) // zoneId
      )( (dayOfMonth, hour, minute, second, nanoOfSecond, zoneId) =>
        ZonedDateTime.of(year, month, dayOfMonth, hour, minute, second, nanoOfSecond, ZoneId.of(zoneId))
      )
  } yield dateTime

  implicit val zonedDateTimeArb: Arbitrary[ZonedDateTime] = Arbitrary(zonedDateTimeGen)

  implicit val keyArb: Arbitrary[Key] = Arbitrary(keyGen)
  implicit val bucketNameArb: Arbitrary[BucketName] = Arbitrary(bucketNameGen)
  implicit val pathArb: Arbitrary[Path] = Arbitrary(Gen.resultOf[BucketName, Key, Path]((a, b) => Path(a, b)))

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
