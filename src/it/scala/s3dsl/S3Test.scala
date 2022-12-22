package s3dsl

import cats.effect.unsafe.IORuntime
import cats.instances.all._
import cats.syntax.all._
import enumeratum.scalacheck._
import eu.timepit.refined.cats.syntax._
import fs2.Stream
import java.net.URI
import java.time.Duration
import org.specs2.execute.AsResult
import org.specs2.matcher.IOMatchers
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import s3dsl.domain.auth.Domain
import s3dsl.domain.auth.Domain._
import s3dsl.domain.auth.Domain.Principal.Provider
import s3dsl.domain.S3._
import s3dsl.Gens._
import scala.util.Random
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.s3.S3AsyncClient
import java.time.ZonedDateTime

object S3Test extends Specification with ScalaCheck with IOMatchers {
  import cats.effect.IO

  private implicit val runtime = IORuntime.global
  private val s3 = S3Dsl
    .interpreter[IO](
      S3AsyncClient
        .builder
        .credentialsProvider(
          StaticCredentialsProvider
          .create(
            AwsBasicCredentials
              .create("minioadmin", "minioadmin")
          )
        )
        .httpClientBuilder(NettyNioAsyncHttpClient.builder.connectionTimeToLive(Duration.ofMinutes(5)))
        .endpointOverride(URI.create("http://127.0.0.1:9000"))
        .build
    )

  "Bucket" in {

    "listBuckets" should {
      "succeed" in {
        val io = withBucket(_ => s3.listBuckets)
        io.unsafeRunSync()
        io should returnValue { l: List[BucketName] => l should not(beEmpty)}
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
          s3.getBucketAcl(bn) should returnValue(Option.empty[s3dsl.domain.S3.AccessControlList])
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

    "delete" should {
      "succeed if an object does not exist" in {
        prop { (key: Key) =>
          val prog: TestProg[Unit] = bucketPath => s3.deleteObject(Path(bucketPath.bucket, key))
          withBucket(prog) should returnOk
        }
      }
    }

    "putObject, getObjectMetadata and deleteObject" should {

      "succeed" in {
        prop { (key: Key, blob: String) =>

          val prog: TestProg[(Boolean, Boolean)] = bucketPath => for {
            path <- IO(Path(bucketPath.bucket, key))
            bytes = blob.getBytes
            _ <- Stream.emits(bytes).covary[IO].through(s3.putObject(path, Nil)).compile.drain
            exists1 <- s3.getObjectMetadata(path).map(_.isDefined)
            _ <- s3.deleteObject(path)
            exists2 <- s3.getObjectMetadata(path).map(_.isDefined)
          } yield (exists1, exists2)

          withBucket(prog) should returnValue((true, false))
        }
      }.set(maxSize = 5).setGen2(Gens.blobGen)

    }

    "putObject" should {

      "succeed" in {
        prop { (key: Key, blob: String) =>
          val headers = List(("Content-Type", "text/plain"))

          val prog: TestProg[Unit] = bucketPath => for {
            path <- IO(Path(bucketPath.bucket, key))
            bytes = blob.getBytes
            _ <- Stream.emits(bytes).covary[IO].through(
              s3.putObject(path, headers)
            ).compile.drain
            _ <- s3.deleteObject(path)
          } yield ()

          withBucket(prog) should returnOk
        }
      }.setGen2(Gens.blobGen)
    }

    // TODO: listObjectsWithCommonPrefixes

    "listObjects" should {

      "succeed" in {
        val keys = List(Key("a.txt"), Key("b.txt"), Key("c.txt"))

        val prog: TestProg[List[ObjectSummary]] = bucketPath => for {
          _ <- keys.parTraverse(k =>
            Stream.emits(k.value.getBytes).covary[IO]
              .through(s3.putObject(bucketPath.copy(key = k), Nil))
              .compile.drain
          )
          list <- s3.listObjects(bucketPath).compile.toList
          _ <- list.traverse(os => s3.deleteObject(os.path))
        } yield list

        withBucket(prog) should returnValue{(l: List[ObjectSummary]) =>
          l.map(_.path.key) should be_==(keys)
        }
      }

      "return an empty stream for a path that does not exist" in {
        prop { key: Key =>
          val prog: TestProg[List[ObjectSummary]] = bucketPath =>
            s3.listObjects(Path(bucketPath.bucket, key)).compile.toList
          withBucket(prog) should returnValue{(l: List[ObjectSummary]) =>
            l should beEmpty
          }
        }.set(maxSize = 2)
      }

    }

    "copyObject" should {

      "succeed" in {
        prop { (src: Key, dest: Key, blob: String) =>
          val bytes = blob.getBytes

          val prog: TestProg[Long] = bucketPath => {
            val srcPath = Path(bucketPath.bucket, src)
            val destPath = Path(bucketPath.bucket, dest)
            for {
              _ <- Stream.emits(bytes).covary[IO].through(s3.putObject(srcPath, Nil)).compile.drain
              _ <- s3.copyObject(srcPath, destPath)
              numBytes <- s3.getObjectMetadata(destPath).map(_.map(_.contentLength).getOrElse(Long.MinValue))
              _ <- List(srcPath, destPath).parTraverse_(s3.deleteObject)
            } yield numBytes
          }
          withBucket(prog) should returnValue{ numBytes: Long =>
            numBytes should be_==(bytes.length)
          }
        }.set(maxSize = 2).setGen3(Gens.blobGen)
      }
    }

    "copyObjectMultipart" should {

      "succeed for file < 5 MiB" in {
        prop { (src: Key, dest: Key, blob: String) =>
          val bytes = blob.getBytes

          val prog: TestProg[Long] = bucketPath => {
            val srcPath = Path(bucketPath.bucket, src)
            val destPath = Path(bucketPath.bucket, dest)
            for {
              _ <- Stream.emits(bytes).covary[IO].through(s3.putObject(srcPath, Nil)).compile.drain
              _ <- s3.copyObjectMultipart(srcPath, destPath, 1)
              numBytes <- s3.getObjectMetadata(destPath).map(_.map(_.contentLength).getOrElse(Long.MinValue))
              _ <- List(srcPath, destPath).parTraverse_(s3.deleteObject)
            } yield numBytes
          }
          withBucket(prog) should returnValue{ numBytes: Long =>
            numBytes should be_==(bytes.length)
          }
        }.set(maxSize = 2).setGen3(Gens.blobGen)
      }

      "succeed for file > 5 MiB" in {
        prop { (src: Key, dest: Key, c: Byte) =>
          val bytes = Array.fill(6 * 1024 * 1024)(c)

          val prog: TestProg[Long] = bucketPath => {
            val srcPath = Path(bucketPath.bucket, src)
            val destPath = Path(bucketPath.bucket, dest)
            for {
              _ <- Stream.emits(bytes).covary[IO].through(s3.putObject(srcPath, Nil)).compile.drain
              _ <- s3.copyObjectMultipart(srcPath, destPath, 5 * 1024 * 1024)
              numBytes <- s3.getObjectMetadata(destPath).map(_.map(_.contentLength).getOrElse(Long.MinValue))
              _ <- List(srcPath, destPath).parTraverse_(s3.deleteObject)
            } yield numBytes
          }
          withBucket(prog) should returnValue{ numBytes: Long =>
            numBytes should be_==(bytes.length)
          }
        }.set(maxSize = 1)
      }
    }

    "getObject" should {

      "succeed" in {

        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val blobSize = blob.getBytes.length

        val prog: TestProg[List[Byte]] = bucketPath => for {
          path <- IO(bucketPath.copy(key = key))
          _ <- Stream.emits(blob.getBytes).covary[IO].through(s3.putObject(path, Nil)).compile.drain
          content <- s3.getObject(path, 1024).compile.toList
          _ <- s3.deleteObject(path)
        } yield content

        withBucket(prog) should returnValue { content: List[Byte] =>
          content should haveSize(blobSize)
        }
      }

      "return Empty Stream if Object does not exist" in {
        prop { key: Key =>
          val prog: TestProg[Boolean] = bucketPath => for {
            path <- IO(bucketPath.copy(key = key))
            l <- s3.getObject(path, 1024).compile.toList
          } yield l.isEmpty

          withBucket(prog) should returnValue {  isEmpty: Boolean =>
            isEmpty should_=== true
          }
        }.set(maxSize = 5)
      }

    }

    "getObjectMetadata" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"

        val prog: TestProg[Option[ObjectMetadata]] = bucketPath => for {
          path <- IO(bucketPath.copy(key = key))
          _ <- Stream.emits(blob.getBytes).covary[IO].through(s3.putObject(path, Nil)).compile.drain
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

          withBucket(prog) should returnValue(Option.empty[ObjectMetadata])
        }.set(maxSize = 5)
      }
    }

    "objectTagging" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val referenceTags = ObjectTags(Map("k1" -> "v1"))

        val prog: TestProg[Option[ObjectTags]] = bucketPath => for {
          path <- IO(bucketPath.copy(key = key))
          _ <- Stream.emits(blob.getBytes).covary[IO].through(s3.putObject(path, Nil)).compile.drain
          _ <- s3.setObjectTags(path, referenceTags)
          tags <- s3.getObjectTags(path)
          _ <- s3.deleteObject(path)
        } yield tags

        withBucket(prog) should returnValue { tagsOpt: Option[ObjectTags] =>
          tagsOpt should beSome(referenceTags)
        }
      }

      "return None if Object does not exist" in {
        prop { key: Key =>
          val prog: TestProg[Option[ObjectTags]] = bucketPath => for {
            path <- IO(bucketPath.copy(key = key))
            tags <- s3.getObjectTags(path)
          } yield tags

          withBucket(prog) should returnValue(Option.empty[ObjectTags])
        }.set(maxSize = 5)
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

  def returnWith[T, R : AsResult](f: T => R): IOMatcher[T] = IOMatcher(f, None)

}

