package s3dsl.domain

import java.nio.charset.StandardCharsets

import eu.timepit.refined.api.Validate
import com.amazonaws.services.s3.internal.BucketNameUtils
import mouse.boolean._

object refined extends s3dsl.domain.string

trait string {

  case class BucketName()

  case object BucketName {
    implicit val bucketNameValidate: Validate.Plain[String, BucketName] = Validate.fromPredicate(
      s => BucketNameUtils.isValidV2BucketName(s),
      s => s"$s is a valid S3 bucket name",
      BucketName()
    )
  }

  case class Key()

  object Key {
    implicit val s3KeyValidate: Validate.Plain[String, Key] = Validate.fromPredicate(
      s => !s.startsWith("/") && (s.getBytes(StandardCharsets.UTF_8).length <= s.endsWith("/").fold(1023, 1024)),
      s => s"$s is a valid S3 key",
      Key()
    )
  }

}