import cats.ApplicativeError
import software.amazon.awssdk.services.s3.model.S3Exception
import java.util.concurrent.CompletionException

package object s3dsl {

  private[s3dsl] implicit class  FSyntax[F[_], A](val f: F[A]) extends AnyVal {

    def handle404(on404: => A)(implicit ev: ApplicativeError[F, Throwable]): F[A] =
      ev.recoverWith(f){
        case e: CompletionException => e.getCause() match {
            case e: S3Exception =>
              e.statusCode match {
                case 404 => ApplicativeError[F, Throwable].pure(on404)
                case _ => println("no matchhhhhhhh"); ApplicativeError[F, Throwable].raiseError(e)
              }
            case e => println(s"DAMN, faced another exception than expected: ${e.toString()}"); ApplicativeError[F, Throwable].raiseError(e)
        }
      }
  }

}
