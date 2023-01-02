# s3dsl

[![Release Artifacts][Badge-SonatypeReleases]][Link-SonatypeReleases]

Minimal, stream based S3 DSL. Depends on [aws-java-sdk-s3](https://github.com/aws/aws-sdk-java/tree/master/aws-java-sdk-s3).

## Getting s3dsl
If you're using SBT, add the following line to your build file:

    libraryDependencies += "com.github.wirthan" %% "s3dsl" % <version>

## Quick Start

### Initialize an interpreter

s3dsl provides a function that returns a [cats-effect](https://github.com/typelevel/cats-effect) interpreter:

```scala
import s3dsl.S3Dsl._
import s3dsl.domain.S3._

import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.presigner.S3Presigner

val asyncClient = S3AsyncClient.builder()...
val presigner = S3Presigner.builder()...

val s3 = interpreter(asyncClient, presigner)
```

### Use the interpreter

```scala
val path = Path(BucketName("mybucket"), Key("blob.txt"))
val blob = "abc".getBytes
] 
val content: Stream[IO, Byte] = for {
  _ <- Stream.emits(blob).covary[IO]
        .through(s3.putObject(path, blob.length.longValue))
        .compile.drain
  content <- s3.getObject(path, 1024)      
} yield content

```

[Link-SonatypeReleases]: https://oss.sonatype.org/content/repositories/releases/com/github/wirthan/s3dsl_2.13/ "Sonatype Releases"
[Badge-SonatypeReleases]: https://img.shields.io/nexus/r/https/oss.sonatype.org/com.github.wirthan/s3dsl_2.13.svg "Sonatype Releases"
