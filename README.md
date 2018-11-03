# s3dsl

[![Release Artifacts][Badge-SonatypeReleases]][Link-SonatypeReleases]

Minimal, stream based S3 DSL. Depends on [aws-java-sdk-s3](https://github.com/aws/aws-sdk-java/tree/master/aws-java-sdk-s3).

## Usage

### Initialize an interpreter

s3dsl provides a function that returns a [cats-effect](https://github.com/typelevel/cats-effect) interpreter:

```scala
import s3dsl.Dsl.S3Dsl._
import s3dsl.domain.S3._

val config = S3Config(
  creds = new BasicAWSCredentials("BQKN8G6V2DQ83DH3AHPN", "GPD7MUZqy6XGtTz7h2QPyJbggGkQfigwDnaJNrgF"),
  endpoint = new EndpointConfiguration("http://localhost:9000", "us-east-1"),
  blockingEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)
)

val cs = IO.contextShift(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3)))
val s3 = interpreter(config)(IO.ioConcurrentEffect(cs), cs)
```

### Use the interpreter

```scala
val path = Path(BucketName("mybucket"), Key("blob.txt"))
val blob = "abc".getBytes

// S3.Object consists of Stream[IO, Byte] and ObjectMetadata
val obj: Option[S3.Object[IO]] = for {
  _ <- Stream.emits(blob).covary[IO]
        .to(s3.putObject(path, blob.length.longValue))
        .compile.drain
  obj <- s3.getObject(path, 1024)      
} yield obj

```

[Link-SonatypeReleases]: https://oss.sonatype.org/content/repositories/releases/com/github/wirthan/s3dsl_2.12/ "Sonatype Releases"
[Badge-SonatypeReleases]: https://img.shields.io/nexus/r/https/oss.sonatype.org/com.github.wirthan/s3dsl.svg "Sonatype Releases"
