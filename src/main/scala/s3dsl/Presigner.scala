package s3dsl

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

object Presigner extends PresignerSyntax {

  def presignGetObjectRequest(presigner: S3Presigner,
                              request: GetObjectRequest,
                              duration: Duration): PresignedGetObjectRequest =
    presigner.presignGetObject(
      GetObjectPresignRequest.builder()
        .signatureDuration(duration)
        .getObjectRequest(request)
        .build()
    )

  def presignPutObjectRequest(presigner: S3Presigner,
                              request: PutObjectRequest,
                              duration: Duration): PresignedPutObjectRequest =
    presigner.presignPutObject(
      PutObjectPresignRequest.builder()
        .signatureDuration(duration)
        .putObjectRequest(request)
        .build()
    )

  def presignCreateMultipartUploadRequest(presigner: S3Presigner,
                                          request: CreateMultipartUploadRequest,
                                          duration: Duration): PresignedCreateMultipartUploadRequest =
    presigner.presignCreateMultipartUpload(
      CreateMultipartUploadPresignRequest.builder()
        .signatureDuration(duration)
        .createMultipartUploadRequest(request)
        .build()
    )


  def presignUploadPartRequest(presigner: S3Presigner,
                               request: UploadPartRequest,
                               duration: Duration): PresignedUploadPartRequest =
    presigner.presignUploadPart(
      UploadPartPresignRequest.builder()
        .signatureDuration(duration)
        .uploadPartRequest(request)
        .build()
    )

  def presignCompleteMultipartUploadRequest(presigner: S3Presigner,
                                            request: CompleteMultipartUploadRequest,
                                            duration: Duration): PresignedCompleteMultipartUploadRequest =
    presigner.presignCompleteMultipartUpload(
      CompleteMultipartUploadPresignRequest.builder()
        .signatureDuration(duration)
        .completeMultipartUploadRequest(request)
        .build()
    )

  def presignAbortMultipartUploadRequest(presigner: S3Presigner,
                                         request: AbortMultipartUploadRequest,
                                         duration: Duration): PresignedAbortMultipartUploadRequest =
    presigner.presignAbortMultipartUpload(
      AbortMultipartUploadPresignRequest.builder()
        .signatureDuration(duration)
        .abortMultipartUploadRequest(request)
        .build()
    )

}

trait PresignerSyntax {
  implicit class GetObjectRequestSyntax(r: GetObjectRequest) {
    def presign(presigner: S3Presigner, duration: Duration): PresignedGetObjectRequest =
      Presigner.presignGetObjectRequest(presigner, r, duration)
  }

  implicit class PutObjectRequestSyntax(r: PutObjectRequest) {
    def presign(presigner: S3Presigner, duration: Duration): PresignedPutObjectRequest =
      Presigner.presignPutObjectRequest(presigner, r, duration)
  }

  implicit class CreateMultipartUploadRequestSyntax(r: CreateMultipartUploadRequest) {
    def presign(presigner: S3Presigner, duration: Duration): PresignedCreateMultipartUploadRequest =
      Presigner.presignCreateMultipartUploadRequest(presigner, r, duration)
  }

  implicit class UploadPartRequestSyntax(r: UploadPartRequest) {
    def presign(presigner: S3Presigner, duration: Duration): PresignedUploadPartRequest =
      Presigner.presignUploadPartRequest(presigner, r, duration)
  }

  implicit class CompleteMultipartUploadRequestSyntax(r: CompleteMultipartUploadRequest) {
    def presign(presigner: S3Presigner, duration: Duration): PresignedCompleteMultipartUploadRequest =
      Presigner.presignCompleteMultipartUploadRequest(presigner, r, duration)
  }

  implicit class AbortMultipartUploadRequestSyntax(r: AbortMultipartUploadRequest) {
    def presign(presigner: S3Presigner, duration: Duration): PresignedAbortMultipartUploadRequest =
      Presigner.presignAbortMultipartUploadRequest(presigner, r, duration)
  }
}
