package s3dsl

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import s3dsl.PresignDsl
import s3dsl.domain.S3.Path
import s3dsl.Gens._
import org.specs2.matcher.ValidatedMatchers
import java.net.URI
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import cats.effect.IO
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import java.time.ZonedDateTime
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
      StaticCredentialsProvider
      .create( AwsBasicCredentials.create(
          "minioadmin", 
          "minioadmin"
      ))
    )
    .endpointOverride(URI.create("http://localhost:9000"))
    .build()

  val interpreter = PresignDsl.interpreter[IO](presigner)
  val expiration = ZonedDateTime.now.plusDays(1L)

  "Signing of Request" should {

    "generate an url with a positive expiration when signing a GET request" in {

      prop { (path: Path) =>
        val request = GetObjectRequest.builder()
                      .bucket(path.bucket.value)
                      .key(path.key.value)
                      .build()
        val presignedUrl = interpreter.presignGetObjectRequest(request,expiration)
        
        extractExpiration(presignedUrl.url) should beSome { s: String =>
          s must startWith("86")
        }
      }

    }

    "generate an url with a positive expiration when signing a PUT request" in {

      prop { (path: Path) =>
        val request = PutObjectRequest.builder()
                      .bucket(path.bucket.value)
                      .key(path.key.value)
                      .build()
        val presignedUrl = interpreter.presignPutObjectRequest(request,expiration)
        
        extractExpiration(presignedUrl.url) should beSome { s: String =>
          s must startWith("86")
        }
      }

    }


    "generate an url with a positive expiration when signing a Create Multipart Upload request" in {

      prop { (path: Path) =>
        val request = CreateMultipartUploadRequest.builder()
                      .bucket(path.bucket.value)
                      .key(path.key.value)
                      .build()
        val presignedUrl = interpreter.presignCreateMultipartUploadRequest(request,expiration)
        
        extractExpiration(presignedUrl.url) should beSome { s: String =>
          s must startWith("86")
        }
      }

    }

    "generate an url with a positive expiration when signing a Upload Part request" in {

      prop { (path: Path) =>
        val request = UploadPartRequest.builder()
                      .bucket(path.bucket.value)
                      .uploadId("testtesttest")
                      .partNumber(1)
                      .key(path.key.value)
                      .build()
        val presignedUrl = interpreter.presignUploadPartRequest(request,expiration)
        
        extractExpiration(presignedUrl.url) should beSome { s: String =>
          s must startWith("86")
        }
      }

    }

    "generate an url with a positive expiration when signing a Complete Multipart Upload request" in {

      prop { (path: Path) =>
        val request = CompleteMultipartUploadRequest.builder()
                      .bucket(path.bucket.value)
                      .uploadId("testtesttest")
                      .key(path.key.value)
                      .build()
        val presignedUrl = interpreter.presignCompleteMultipartUploadRequest(request,expiration)
        
        extractExpiration(presignedUrl.url) should beSome { s: String =>
          s must startWith("86")
        }
      }

    }

    "generate an url with a positive expiration when signing a Abort Multipart Upload request" in {

      prop { (path: Path) =>
        val request = AbortMultipartUploadRequest.builder()
                      .bucket(path.bucket.value)
                      .uploadId("testtesttest")
                      .key(path.key.value)
                      .build()
        val presignedUrl = interpreter.presignAbortMultipartUploadRequest(request,expiration)
        
        extractExpiration(presignedUrl.url) should beSome { s: String =>
          s must startWith("86")
        }
      }

    }

  }

  private def extractExpiration(url: URL) = url.toString.split("X-Amz-Expires=")
                                            .toList
                                            .drop(1)
                                            .headOption

}
