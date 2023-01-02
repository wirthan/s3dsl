package s3dsl

import java.time.ZonedDateTime
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedCreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedCompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedAbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.CreateMultipartUploadPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.CompleteMultipartUploadPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.AbortMultipartUploadPresignRequest


trait PresignDsl[F[_]]{

  def presignGetObjectRequest(request: GetObjectRequest, expiration: ZonedDateTime): PresignedGetObjectRequest

  def presignPutObjectRequest(request: PutObjectRequest, expiration: ZonedDateTime): PresignedPutObjectRequest

  def presignCreateMultipartUploadRequest(request: CreateMultipartUploadRequest, expiration: ZonedDateTime): PresignedCreateMultipartUploadRequest

  def presignUploadPartRequest(request: UploadPartRequest, expiration: ZonedDateTime): PresignedUploadPartRequest

  def presignCompleteMultipartUploadRequest(request: CompleteMultipartUploadRequest, expiration: ZonedDateTime): PresignedCompleteMultipartUploadRequest

  def presignAbortMultipartUploadRequest(request: AbortMultipartUploadRequest, expiration: ZonedDateTime): PresignedAbortMultipartUploadRequest

}

object PresignDsl {
  
  def interpreter[
    F[_]
  ](presigner: S3Presigner) = new PresignDsl[F]{

    override def presignGetObjectRequest(request: GetObjectRequest, expiration: ZonedDateTime): PresignedGetObjectRequest =
      presigner.presignGetObject(
        GetObjectPresignRequest.builder()
        .signatureDuration(expiration.toDuration())
        .getObjectRequest(request)
        .build()
      )

    override def presignPutObjectRequest(request: PutObjectRequest, expiration: ZonedDateTime): PresignedPutObjectRequest =
      presigner.presignPutObject(
        PutObjectPresignRequest.builder()
        .signatureDuration(expiration.toDuration())
        .putObjectRequest(request)
        .build()
      )

    override def presignCreateMultipartUploadRequest(request: CreateMultipartUploadRequest, expiration: ZonedDateTime): PresignedCreateMultipartUploadRequest =
      presigner.presignCreateMultipartUpload(
        CreateMultipartUploadPresignRequest.builder()
        .signatureDuration(expiration.toDuration())
        .createMultipartUploadRequest(request)
        .build()
      )

    override def presignUploadPartRequest(request: UploadPartRequest, expiration: ZonedDateTime): PresignedUploadPartRequest =
      presigner.presignUploadPart(
        UploadPartPresignRequest.builder()
        .signatureDuration(expiration.toDuration())
        .uploadPartRequest(request)
        .build()
      )

    override def presignCompleteMultipartUploadRequest(request: CompleteMultipartUploadRequest, expiration: ZonedDateTime): PresignedCompleteMultipartUploadRequest =
      presigner.presignCompleteMultipartUpload(
        CompleteMultipartUploadPresignRequest.builder()
        .signatureDuration(expiration.toDuration())
        .completeMultipartUploadRequest(request)
        .build()
      )

    override def presignAbortMultipartUploadRequest(request: AbortMultipartUploadRequest, expiration: ZonedDateTime): PresignedAbortMultipartUploadRequest =
      presigner.presignAbortMultipartUpload(
        AbortMultipartUploadPresignRequest.builder()
        .signatureDuration(expiration.toDuration())
        .abortMultipartUploadRequest(request)
        .build()
      )

  }

  private implicit class ZonedDateTimeSyntax(zdt: ZonedDateTime){

    def toDuration(): Duration =  Duration.between(ZonedDateTime.now(), zdt)

  }

}
