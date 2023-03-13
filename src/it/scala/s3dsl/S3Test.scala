package s3dsl

import cats.instances.all._
import cats.syntax.all._
import enumeratum.scalacheck._
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.s3.model.{Bucket, CommonPrefix, HeadObjectResponse, S3Object}
import software.amazon.awssdk.services.s3.S3AsyncClient

import java.util.UUID

object S3Test extends Specification with ScalaCheck with IOMatchers {
  import cats.effect.IO

  private val s3 = S3Dsl
    .interpreter[IO](
      S3AsyncClient
        .builder
        .credentialsProvider(
          StaticCredentialsProvider
          .create(
            AwsBasicCredentials
              .create("BQKN8G6V2DQ83DH3AHPN", "GPD7MUZqy6XGtTz7h2QPyJbggGkQfigwDnaJNrgF")
          )
        )
        .httpClientBuilder(NettyNioAsyncHttpClient.builder.connectionTimeToLive(Duration.ofMinutes(5)))
        .endpointOverride(URI.create("http://127.0.0.1:9000"))
        .build
    )

  "Bucket" in {

    "listBuckets" should {
      "succeed" in {
        val buckets = withRandomBucket(_ => s3.listBuckets)
        buckets should returnValue { l: List[Bucket] =>
          l should not(beEmpty)
        }
      }
    }

    "create, delete, doesBucketExist" should {

      "succeed" in {
        val prog = for {
          name <- randomBucketName
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

          val prog: TestProg[Option[PolicyRead]] = bucketName => for {
            _ <- s3.setBucketPolicy(bucketName, policyWrite)
            policyRead <- s3.getBucketPolicy(bucketName)
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
          val prog: TestProg[Unit] = bucketName => s3.deleteObject(Path(bucketName, key))
          withRandomBucket(prog) should returnOk
        }
      }
    }

    "putObject, getObjectMetadata and deleteObject" should {

      "succeed" in {
        prop { (key: Key, blob: String) =>

          val prog: TestProg[(Boolean, Boolean)] = bucketName => for {
            path <- IO(Path(bucketName, key))
            bytes = blob.getBytes
            _ <- Stream.emits(bytes).covary[IO].through(s3.putObject(path, bytes.length.longValue, Nil)).compile.drain
            exists1 <- s3.headObject(path).map(_.isDefined)
            _ <- s3.deleteObject(path)
            exists2 <- s3.headObject(path).map(_.isDefined)
          } yield (exists1, exists2)

          withRandomBucket(prog) should returnValue((true, false))
        }
      }.set(maxSize = 5).setGen2(Gens.blobGen)

    }

    "putObject" should {

      "succeed" in {
        prop { (key: Key, blob: String) =>
          val headers = List(("Content-Type", "text/plain"))

          val prog: TestProg[List[Byte]] = bucketName => for {
            path <- IO(Path(bucketName, key))
            bytes = blob.getBytes
            _ <- Stream.emits(bytes).covary[IO].through(
              s3.putObject(path, bytes.length.longValue, headers)
            ).compile.drain
            obj <- s3.getObject(path, 1028).compile.toList
            _ <- s3.deleteObject(path)
          } yield obj

          withRandomBucket(prog) should returnValue{ bytes : List[Byte] =>
            bytes should haveSize(blob.getBytes.length)
          }

        }
      }.setGen2(Gens.blobGen)
    }

    // TODO: listObjectsWithCommonPrefixes

    "listObjects / listObjectsWithCommonPrefixes" should {

      "succeed" in {
        val keys = List(Key("a.txt"), Key("b.txt"), Key("c.txt"))

        val prog: TestProg[List[S3Object]] = bucketName=> for {
          _ <- keys.parTraverse(k =>
            Stream.emits(k.value.getBytes).covary[IO]
              .through(s3.putObject(bucketName.path(k), k.value.getBytes.length.longValue, Nil))
              .compile.drain
          )
          list <- s3.listObjects(bucketName).compile.toList
          _ <- list
            .traverse(obj => s3.deleteObject(bucketName.path(Key(obj.key))))
        } yield list

        withRandomBucket(prog) should returnValue{(l: List[S3Object]) =>
          l.map(obj => Key(obj.key)) should be_==(keys)
        }
      }

      "return an empty stream for a prefix that does not exist" in {
        prop { key: Key =>
          val prog: TestProg[List[Either[S3Object, CommonPrefix]]] = bucketName =>
            s3.listObjectsWithCommonPrefixes(bucketName, key.value).compile.toList
          withRandomBucket(prog) should returnValue{(l: List[Either[S3Object, CommonPrefix]]) =>
            l should beEmpty
          }
        }.set(maxSize = 2)
      }

    }
    "copyObject" should {

      "succeed" in {
        prop { (src: Key, dest: Key, blob: String) =>
          val bytes = blob.getBytes

          val prog: TestProg[Option[Long]] = bucketName => {
            val srcPath = Path(bucketName, src)
            val destPath = Path(bucketName, dest)
            for {
              _ <- Stream.emits(bytes).covary[IO].through(s3.putObject(srcPath, bytes.length.longValue, Nil)).compile.drain
              _ <- s3.copyObject(srcPath, destPath)
              numBytes <- s3.headObject(destPath).map(_.map(_.contentLength.longValue))
              _ <- List(srcPath, destPath).parTraverse_(s3.deleteObject)
            } yield numBytes
          }
          withRandomBucket(prog) should returnValue{ numBytes: Option[Long] =>
            numBytes should be_==(bytes.length.some)
          }
        }.set(maxSize = 2).setGen3(Gens.blobGen)
      }

      "succeed for file < 5 MiB" in {
        prop { (src: Key, dest: Key, blob: String) =>
          val bytes = blob.getBytes

          val prog: TestProg[Option[Long]] = bucketName => {
            val srcPath = Path(bucketName, src)
            val destPath = Path(bucketName, dest)
            for {
              _ <- Stream.emits(bytes).covary[IO].through(s3.putObject(srcPath, bytes.length.longValue, Nil)).compile.drain
              _ <- s3.copyObject(srcPath, destPath)
              numBytes <- s3.headObject(destPath).map(_.map(_.contentLength.longValue))
              _ <- List(srcPath, destPath).parTraverse_(s3.deleteObject)
            } yield numBytes
          }
          withRandomBucket(prog) should returnValue{ numBytes: Option[Long] =>
            numBytes should be_==(bytes.length.some)
          }
        }.set(maxSize = 2).setGen3(Gens.blobGen)
      }

      "succeed for file > 5 MiB" in {
        prop { (src: Key, dest: Key, c: Byte) =>
          val bytes = Array.fill(6 * 1024 * 1024)(c)

          val prog: TestProg[Option[Long]] = bucketName => {
            val srcPath = Path(bucketName, src)
            val destPath = Path(bucketName, dest)
            for {
              _ <- Stream.emits(bytes).covary[IO].through(s3.putObject(srcPath, bytes.length.longValue, Nil)).compile.drain
              _ <- s3.copyObject(srcPath, destPath)
              numBytes <- s3.headObject(destPath).map(_.map(_.contentLength.longValue))
              _ <- List(srcPath, destPath).parTraverse_(s3.deleteObject)
            } yield numBytes
          }
          withRandomBucket(prog) should returnValue{ numBytes: Option[Long] =>
            numBytes should be_==(bytes.length.some)
          }
        }.set(maxSize = 1)
      }
    }

    "getObject" should {

      "succeed" in {

        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val blobSize = blob.getBytes.length

        val prog: TestProg[List[Byte]] = bucketName => {
          val path = bucketName.path(key)
          for {
            _ <- Stream.emits(blob.getBytes).covary[IO].through(s3.putObject(path, blob.getBytes.length.longValue, Nil)).compile.drain
            content <- s3.getObject(path, 1024).compile.toList
            _ <- s3.deleteObject(path)
          } yield content
        }

        withRandomBucket(prog) should returnValue { content: List[Byte] =>
          content should haveSize(blobSize)
        }
      }

      "return Empty Stream if Object does not exist" in {
        prop { key: Key =>
          val prog: TestProg[Boolean] = bucketName => {
            val path = bucketName.path(key)
            s3.getObject(path, 1024).compile.toList.map(_.isEmpty)
          }

          withRandomBucket(prog) should returnValue {  isEmpty: Boolean =>
            isEmpty should_=== true
          }
        }.set(maxSize = 5)
      }

    }

    "headObject" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"

        val prog: TestProg[Option[HeadObjectResponse]] = bucketName => for {
          path <- IO(bucketName.path(key))
          _ <- Stream.emits(blob.getBytes).covary[IO].through(s3.putObject(path, blob.getBytes.length.longValue, Nil)).compile.drain
          meta <- s3.headObject(path)
          _ <- s3.deleteObject(path)
        } yield meta

        withRandomBucket(prog) should returnValue { meta: Option[HeadObjectResponse] =>
          meta should beSome
        }
      }

      "return None if Object does not exist" in {
        prop { key: Key =>
          val prog: TestProg[Option[HeadObjectResponse]] = bucketName =>
            s3.headObject(Path(bucketName, key))

          withRandomBucket(prog) should returnValue(Option.empty[HeadObjectResponse])
        }.set(maxSize = 5)
      }
    }

    "objectTagging" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val referenceTags = ObjectTags(Map("k1" -> "v1"))

        val prog: TestProg[Option[ObjectTags]] = bucketName => {
          val path = Path(bucketName, key)
          for {
            _ <- Stream.emits(blob.getBytes).covary[IO].through(s3.putObject(path, blob.getBytes.length.longValue, Nil)).compile.drain
            _ <- s3.setObjectTags(path, referenceTags)
            tags <- s3.getObjectTags(path)
            _ <- s3.deleteObject(path)
          } yield tags
        }

        withRandomBucket(prog) should returnValue { tagsOpt: Option[ObjectTags] =>
          tagsOpt should beSome(referenceTags)
        }
      }

      "return None if Object does not exist" in {
        prop { key: Key =>
          val prog: TestProg[Option[ObjectTags]] = bucketName =>
            s3.getObjectTags(Path(bucketName, key))

          withRandomBucket(prog) should returnValue(Option.empty[ObjectTags])
        }.set(maxSize = 5)
      }

    }

  }

  private type TestProg[X] = BucketName => IO[X]

  private def withRandomBucket[X](f: TestProg[X]): IO[X] = for {
    bn <- randomBucketName
    x <- withBucket(bn, f)
  } yield x

  private def withBucket[X](bn: BucketName, f: TestProg[X]): IO[X] =
    s3.createBucket(bn).bracket(_ => f(bn))(_ => s3.deleteBucket(bn))

  private def randomBucketName: IO[BucketName] = for {
    timestamp <- IO(System.currentTimeMillis)
    uuid <- IO(UUID.randomUUID.toString)
  } yield BucketName(s"test-$timestamp-$uuid")

  def returnWith[T, R : AsResult](f: T => R): IOMatcher[T] = IOMatcher(f, None)

}

