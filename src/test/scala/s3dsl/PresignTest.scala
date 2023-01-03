package s3dsl

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import s3dsl.domain.S3.Path
import s3dsl.Presigner._
import s3dsl.Gens._
import cats.implicits._
import org.specs2.matcher.ValidatedMatchers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import java.time.Duration
import java.net.URL
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest

object PresignTest extends Specification with ValidatedMatchers with ScalaCheck {
  
  val presigner = 
    S3Presigner
    .builder()
    .credentialsProvider(
      StaticCredentialsProvider.create(AwsBasicCredentials.create("minioadmin", "minioadmin"))
    )
    .build()

  val duration = Duration.ofHours(12)

  "Signing of Request" should {

    "generate an url with a positive expiration when signing a GET request" in {

      prop { (path: Path) =>
        val presignedUrl = GetObjectRequest.builder()
          .bucket(path.bucket.value)
          .key(path.key.value)
          .build()
          .presign(presigner, duration)
        
        extractExpiration(presignedUrl.url) should beSome { d: Long =>
          d must between(duration.getSeconds - 1, duration.getSeconds)
        }
      }
    }

    "generate an url with a positive expiration when signing a PUT request" in {

      prop { (path: Path) =>
        val presignedUrl = PutObjectRequest.builder()
          .bucket(path.bucket.value)
          .key(path.key.value)
          .build()
          .presign(presigner, duration)
        
        extractExpiration(presignedUrl.url) should beSome { d: Long =>
          d must between(duration.getSeconds - 1, duration.getSeconds)
        }
      }
    }


    "generate an url with a positive expiration when signing a Create Multipart Upload request" in {

      prop { (path: Path) =>
        val presignedUrl = CreateMultipartUploadRequest.builder()
          .bucket(path.bucket.value)
          .key(path.key.value)
          .build()
          .presign(presigner, duration)
        
        extractExpiration(presignedUrl.url) should beSome { d: Long =>
          d must between(duration.getSeconds - 1, duration.getSeconds)
        }
      }
    }

    "generate an url with a positive expiration when signing a Upload Part request" in {

      prop { (path: Path) =>
        val presignedUrl = UploadPartRequest.builder()
          .bucket(path.bucket.value)
          .uploadId("testtesttest")
          .partNumber(1)
          .key(path.key.value)
          .build()
          .presign(presigner, duration)
        
        extractExpiration(presignedUrl.url) should beSome { d: Long =>
          d must between(duration.getSeconds - 1, duration.getSeconds)
        }
      }
    }

    "generate an url with a positive expiration when signing a Complete Multipart Upload request" in {

      prop { (path: Path) =>
        val presignedUrl = CompleteMultipartUploadRequest.builder()
          .bucket(path.bucket.value)
          .uploadId("testtesttest")
          .key(path.key.value)
          .build()
          .presign(presigner, duration)
        
        extractExpiration(presignedUrl.url) should beSome { d: Long =>
          d must between(duration.getSeconds - 1, duration.getSeconds)
        }
      }
    }

    "generate an url with a positive expiration when signing a Abort Multipart Upload request" in {

      prop { (path: Path) =>
        val presignedUrl = AbortMultipartUploadRequest.builder()
          .bucket(path.bucket.value)
          .uploadId("testtesttest")
          .key(path.key.value)
          .build()
          .presign(presigner, duration)
        
        extractExpiration(presignedUrl.url) should beSome { d: Long=>
          d must between(duration.getSeconds - 1, duration.getSeconds)
        }
      }
    }

  }

  private def extractExpiration(url: URL): Option[Long] =
    url.getQuery.split("X-Amz-Expires=").toList
      .tail
      .headOption
      .map(s => s.substring(0, s.indexOf("&")))
      .flatMap(s => Either.catchNonFatal(java.lang.Long.parseLong(s)).toOption)

}
