import cats.ApplicativeError
import cats.implicits._
import com.amazonaws.services.s3.model.AmazonS3Exception
import software.amazon.awssdk.services.s3.model.S3Exception


package object s3dsl {

  private[s3dsl] implicit class FSyntax[F[_], A](val f: F[A]) extends AnyVal {

    def handle404(implicit ev: ApplicativeError[F, Throwable]): F[Option[A]] = f.map(Option.apply)
      .recoverWith {
        case e: AmazonS3Exception => altIf404(e.getStatusCode, e, None)
        case e: S3Exception => altIf404(e.statusCode, e, None)
      }


     def exists(implicit ev: ApplicativeError[F, Throwable]): F[Boolean] = f.map(_ => true)
      .recoverWith {
        case e: S3Exception => altIf404(e.statusCode, e, false)
      }

    private def altIf404[X](status: Int, t: Throwable, alt: => X)
                           (implicit ev: ApplicativeError[F, Throwable]): F[X] = status match {
      case 404 => ApplicativeError[F, Throwable].pure(alt)
      case _ => ApplicativeError[F, Throwable].raiseError(t)
    }
  }

}
