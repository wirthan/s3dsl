package s3dsl

import java.time.ZonedDateTime
import java.util.concurrent.Executors

import S3Dsl._
import s3dsl.domain.S3._
import s3dsl.Gens._
import enumeratum.scalacheck._
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
import s3dsl.domain.auth.Domain
import s3dsl.domain.auth.Domain.Principal.Provider
import s3dsl.domain.auth.Domain._

object S3Test extends Specification with ScalaCheck with IOMatchers {
  import cats.effect.IO

  val ecBlocking = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  private val config = S3Config(
    creds = new BasicAWSCredentials("BQKN8G6V2DQ83DH3AHPN", "GPD7MUZqy6XGtTz7h2QPyJbggGkQfigwDnaJNrgF"),
    endpoint = new EndpointConfiguration("http://localhost:9000", "us-east-1"),
    blockingEc = ecBlocking
  )

  private val cs = IO.contextShift(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3)))
  private val s3 = interpreter(config, cs)(IO.ioConcurrentEffect(cs))
  private implicit val par = IO.ioParallel(cs)

  "Bucket" in {

    "listBuckets" should {
      "succeed" in {
        withBucket(_ => s3.listBuckets) should returnValue { l: List[BucketName] => l should not(beEmpty)}
      }
    }

    "create, delete, doesBucketExist" should {

      "succeed" in {
        val prog = for {
          name <- bucketName
          _ <- s3.createBucket(name)
          exists1 <- s3.doesBucketExist(name)
          _ <- s3.deleteBucket(name)
          exists2 <- s3.doesBucketExist(name)
        } yield (exists1, exists2)

        prog should returnValue((true, false))
      }
    }

    "getBucketAcl" should {

      "return Some if bucket exists" in {
        prop { bn: BucketName =>
          val prog = for {
            _ <- s3.createBucket(bn)
            acl <- s3.getBucketAcl(bn)
            _ <- s3.deleteBucket(bn)
          } yield acl

          prog should returnValue{ acl: Option[AccessControlList] =>
            acl should beSome
          }
        }
      }.set(maxSize = 8)

      "return None if bucket does not exist" in {
        prop { bn: BucketName =>
          s3.getBucketAcl(bn) should returnValue(None)
        }
      }

    }

    "getting and setting Bucket Policy" should {

      "succeed for a simple case" in {
        prop { bn: BucketName =>
          val policyWrite = PolicyWrite(
            id = Some("1"),
            version = Policy.Version.defaultVersion,
            statements = List(
              StatementWrite(
                id = "1",
                effect = Domain.Effect.Allow,
                principals = Set(Principal(Provider("AWS"), Principal.Id("*"))),
                actions = Set(S3Action.GetBucketLocation, S3Action.ListObjects),
                resources = Set(Resource(s"arn:aws:s3:::${bn.value}")),
                conditions = Set()
              ),
              StatementWrite(
                id = "2",
                effect = Domain.Effect.Allow,
                principals = Set(Principal(Provider("AWS"), Principal.Id("*"))),
                actions = Set(S3Action.GetObject),
                resources = Set(Resource(s"arn:aws:s3:::${bn.value}/*")),
                conditions = Set()
              )
            )
          )

          val prog: TestProg[Option[PolicyRead]] = bucketPath => for {
            _ <- s3.setBucketPolicy(bucketPath.bucket, policyWrite)
            policyRead <- s3.getBucketPolicy(bucketPath.bucket)
          } yield policyRead

          withBucket(bn, prog) should returnValue{ policy: Option[PolicyRead] =>
            policy should beSome
          }
        }
      }.set(maxSize = 8)
    }
  }

  "Object" in {

    "put, doesObjectExist and delete" should {

      "succeed" in {
        prop { (key: Key, blob: String) =>

          val prog: TestProg[(Boolean, Boolean)] = bucketPath => for {
            path <- IO(Path(bucketPath.bucket, key))
            bytes = blob.getBytes
            _ <- Stream.emits(bytes).covary[IO].to(s3.putObject(path, bytes.length.longValue)).compile.drain
            exists1 <- s3.doesObjectExist(path)
            _ <- s3.deleteObject(path)
            exists2 <- s3.doesObjectExist(path)
          } yield (exists1, exists2)

          withBucket(prog) should returnValue((true, false))
        }
      }.set(maxSize = 5).setGen2(Gens.blobGen)

    }

    "listObjects" should {

      "succeed" in {
        val keys = List(Key("a.txt"), Key("b.txt"), Key("c.txt"))

        val prog: TestProg[List[ObjectSummary]] = bucketPath => for {
          _ <- keys.parTraverse(k =>
            Stream.emits(k.value.getBytes).covary[IO]
              .to(s3.putObject(bucketPath.copy(key = k), k.value.getBytes.length.longValue))
              .compile.drain
          )
          list <- s3.listObjects(bucketPath).compile.toList
          _ <- list.traverse(os => s3.deleteObject(os.path))
        } yield list

        withBucket(prog) should returnValue{(l: List[ObjectSummary]) =>
          l.map(_.path.key) should be_==(keys)
        }
      }

    }

    "copy object" should {

      "succeed" in {
        prop { (src: Key, dest: Key, blob: String) =>
          val prog: TestProg[(Boolean, Boolean)] = bucketPath => {
            val bytes = blob.getBytes
            val srcPath = Path(bucketPath.bucket, src)
            val destPath = Path(bucketPath.bucket, dest)
            for {
              _ <- Stream.emits(bytes).covary[IO].to(s3.putObject(srcPath, bytes.length.longValue)).compile.drain
              _ <- s3.copyObject(srcPath, destPath)
              srcExists <- s3.doesObjectExist(srcPath)
              destExists <- s3.doesObjectExist(destPath)
              _ <- List(srcPath, destPath).parTraverse_(s3.deleteObject)
            } yield (srcExists, destExists)
          }
          withBucket(prog) should returnValue((true, true))
        }.set(maxSize = 5).setGen3(Gens.blobGen)
      }
    }

    "getObject" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val blobSize = blob.getBytes.length

        val prog: TestProg[Option[Object[IO]]] = bucketPath => for {
          path <- IO(bucketPath.copy(key = key))
          _ <- Stream.emits(blob.getBytes).covary[IO].to(s3.putObject(path, blobSize.longValue)).compile.drain
          obj <- s3.getObject(path, 1024)
          _ <- s3.deleteObject(path)
        } yield obj

        withBucket(prog) should returnValue { objO: Option[Object[IO]] =>
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

      "return None if Object does not exist" in {
        prop { key: Key =>
          val prog: TestProg[Option[Object[IO]]] = bucketPath => for {
            path <- IO(bucketPath.copy(key = key))
            obj <- s3.getObject(path, 1024)
          } yield obj

          withBucket(prog) should returnValue(None)
        }.set(maxSize = 5)
      }

    }

    "getObjectMetadata" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val blobSize = blob.getBytes.length

        val prog: TestProg[Option[ObjectMetadata]] = bucketPath => for {
          path <- IO(bucketPath.copy(key = key))
          _ <- Stream.emits(blob.getBytes).covary[IO].to(s3.putObject(path, blobSize.longValue)).compile.drain
          meta <- s3.getObjectMetadata(path)
          _ <- s3.deleteObject(path)
        } yield meta

        withBucket(prog) should returnValue { metaO: Option[ObjectMetadata] =>
          metaO should beSome
        }
      }

      "return None if Object does not exist" in {
        prop { key: Key =>
          val prog: TestProg[Option[ObjectMetadata]] = bucketPath => for {
            path <- IO(bucketPath.copy(key = key))
            meta <- s3.getObjectMetadata(path)
          } yield meta

          withBucket(prog) should returnValue(None)
        }.set(maxSize = 5)
      }
    }

    "generatePresignedUrl" should {

      "succeed" in {
        prop { (path: Path, expiration: ZonedDateTime, method: HTTPMethod) =>
          s3.generatePresignedUrl(path, expiration, method) should returnOk[URL]
        }
      }

    }

  }

  private type TestProg[X] = Path => IO[X]

  private def withBucket[X](f: TestProg[X]): IO[X] = for {
    bucketPath <- bucketName.map(Path(_, Key.empty))
    x <- withBucket(bucketPath.bucket, f)
  } yield x

  private def withBucket[X](bn: BucketName, f: TestProg[X]): IO[X] =
    s3.createBucket(bn).bracket(_ => f(Path(bn, Key.empty)))(_ => s3.deleteBucket(bn))

  private def bucketName = IO(
    BucketName.validate(s"test-${System.currentTimeMillis}-${Random.nextInt(9999999).toString}")
      .fold(_ => sys.error("err"), identity)
  )

}

