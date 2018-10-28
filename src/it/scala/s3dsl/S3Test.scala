package s3dsl

import java.util.concurrent.Executors
import Dsl.S3Dsl._
import s3dsl.domain.S3._
import s3dsl.Gens._
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import eu.timepit.refined.cats.syntax._
import org.specs2.mutable.Specification
import fs2.Stream
import org.specs2.ScalaCheck
import org.specs2.matcher.IOMatchers
import scala.concurrent.ExecutionContext
import scala.util.Random
import cats.syntax.all._
import cats.instances.all._

object S3Test extends Specification with ScalaCheck with IOMatchers {
  import cats.effect.IO

  val ecBlocking = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  private val config = S3Config(
    creds = new BasicAWSCredentials("BQKN8G6V2DQ83DH3AHPN", "GPD7MUZqy6XGtTz7h2QPyJbggGkQfigwDnaJNrgF"),
    endpoint = new EndpointConfiguration("http://localhost:9000", ""),
    blockingEc = ecBlocking
  )

  private val cs = IO.contextShift(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3)))
  private val s3 = interpreter(config)(IO.ioConcurrentEffect(cs), cs)
  private implicit val par = IO.ioParallel(cs)

  private lazy val bucketName = IO(
    BucketName.validate(s"test-${System.currentTimeMillis}-${Random.nextInt(9999999).toString}")
      .fold(_ => sys.error("err"), identity)
  )

  "Bucket" in {

    "listBuckets" should {
      "succeed" in {
        s3.listBuckets should returnValue { l: List[BucketName] => l should not(beEmpty)}
      }
    }

    "create, delete, doesBucketExist" should {
      "succeed" in {

        val task = for {
          name <- bucketName
          _ <- s3.createBucket(name)
          exists1 <- s3.doesBucketExist(name)
          _ <- s3.deleteBucket(name)
          exists2 <- s3.doesBucketExist(name)
        } yield (exists1, exists2)

        task should returnValue((true, false))
      }
    }
  }

  "Object" in {

    "create, read and delete" should {
      "succeed" in {
        prop { (key: Key, blob: String) =>

          val bytes = blob.getBytes

          val task = for {
            path <- bucketName.map(Path(_, key))
            _ <- s3.createBucket(path.bucket)
            _ <- Stream.emits(bytes).covary[IO].to(s3.putObject(path, bytes.length.longValue)).compile.drain
            exists1 <- s3.doesObjectExist(path)
            _ <- s3.deleteObject(path)
            exists2 <- s3.doesObjectExist(path)
            _ <- s3.deleteBucket(path.bucket)
          } yield (exists1, exists2)

          task should returnValue((true, false))
        }
      }.set(minTestsOk = 3, maxSize = 5).setGen2(Gens.blobGen)
    }

    "list" should {
      "succeed" in {
        val keys = List(Key("a.txt"), Key("b.txt"), Key("c.txt"))
          val task = for {
            bucketPath <- bucketName.map(Path(_, Key.empty))
            _ <- s3.createBucket(bucketPath.bucket)
            _ <- keys.parTraverse(k =>
              Stream.emits(k.value.getBytes).covary[IO].to(s3.putObject(bucketPath.copy(key = k), k.value.getBytes.length.longValue)).compile.drain
            )
            list <- s3.list(bucketPath).compile.toList
            _ <- list.traverse(os => s3.deleteObject(os.path))
            _ <- s3.deleteBucket(bucketPath.bucket)
          } yield list

          task should returnValue{(l: List[ObjectSummary]) =>
            l.map(_.path.key) should be_==(keys)
          }
      }
    }

    "get" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val blobSize = blob.getBytes.length

        val task = for {
          path <- bucketName.map(Path(_, key))
          _ <- s3.createBucket(path.bucket)
          _ <- Stream.emits(blob.getBytes).covary[IO].to(s3.putObject(path, blobSize.longValue)).compile.drain
          obj <- s3.getObject(path, 1024)
          _ <- s3.deleteObject(path)
          _ <- s3.deleteBucket(path.bucket)
        } yield obj

        task should returnValue { objO: Option[Object[IO]] =>
          objO should beSome{ obj: Object[IO] =>
            val bytes = obj.stream.compile.toList

            bytes should returnValue { l: List[Byte] =>
              l should haveSize(blobSize)
            }
            obj.meta.contentLength should be_>=(blobSize.longValue)
            obj.meta.contentType aka "ContentType" should beSome
          }
        }
      }
    }

    "getObjectMetadata" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val blobSize = blob.getBytes.length

        val task = for {
          path <- bucketName.map(Path(_, key))
          _ <- s3.createBucket(path.bucket)
          _ <- Stream.emits(blob.getBytes).covary[IO].to(s3.putObject(path, blobSize.longValue)).compile.drain
          meta <- s3.getObjectMetadata(path)
          _ <- s3.deleteObject(path)
          _ <- s3.deleteBucket(path.bucket)
        } yield meta

        task should returnValue { metaO: Option[ObjectMetadata] =>
          metaO should beSome
        }
      }
    }

  }

}

