package s3dsl

import cats.effect.Sync
import fs2.{Pipe, Sink, Stream}

private[s3dsl] object FS2 {
  import cats.syntax.functor._

  def evalF[F[_], I](a: I)(implicit F: Sync[F]): Stream[F, I] = Stream.eval(F.delay(a))

  def evalFU[F[_], I](a: I)(implicit F: Sync[F]): Stream[F, Unit] = Stream.eval(F.delay(a).void)

  def liftSink[F[_], I](f: I => F[Unit]): Sink[F, I] = liftPipe[F, I, Unit](f)

  def liftPipe[F[_], I, O](f: I => F[O]): Pipe[F, I, O] = _.evalMap (f(_))

}
