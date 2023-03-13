import cats.ApplicativeThrow
import cats.syntax.all._
import software.amazon.awssdk.services.s3.model.S3Exception

package object s3dsl {

  private[s3dsl] implicit class FSyntax[F[_] : ApplicativeThrow, A](val f: F[A]) {

    def handle404(on404: => A): F[A] =
      f.recoverWith {
        case e: S3Exception =>
          e.statusCode match {
            case 404 => on404.pure
            case _ => e.raiseError
          }
        case e => e.raiseError
      }
  }

}
