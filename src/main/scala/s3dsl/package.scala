import cats.ApplicativeError
import com.amazonaws.services.s3.model.AmazonS3Exception


package object s3dsl {

  implicit class  FSyntax[F[_], A](val f: F[A]) extends AnyVal {

    def handle404(on404: => A)(implicit ev: ApplicativeError[F, Throwable]): F[A] =
      ev.recoverWith(f){ case e: AmazonS3Exception =>
        e.getStatusCode match {
          case 404 => ApplicativeError[F, Throwable].pure(on404)
          case _ => ApplicativeError[F, Throwable].raiseError(e)
        }
      }
  }

}
