import cats.ApplicativeError
import software.amazon.awssdk.services.s3.model.S3Exception

package object s3dsl {

  private[s3dsl] implicit class  FSyntax[F[_], A](val f: F[A]) extends AnyVal {

    def handle404(on404: => A)(implicit ev: ApplicativeError[F, Throwable]): F[A] =
      ev.recoverWith(f){ case e: S3Exception =>
        e.statusCode match {
          case 404 => ApplicativeError[F, Throwable].pure(on404)
          case _ => ApplicativeError[F, Throwable].raiseError(e)
        }
      }
  }

}
