# s3dsl

[![Release Artifacts][Badge-SonatypeReleases]][Link-SonatypeReleases]

Minimal, stream based S3 DSL. Depends on [aws-sdk-java-v2](https://github.com/aws/aws-sdk-java-v2).

## Getting s3dsl
If you're using SBT, add the following line to your build file:

    libraryDependencies += "com.github.wirthan" %% "s3dsl" % <version>

## Features
 - Final tagless DSL for basic operations on S3 buckets and objects
 - Scala wrapper around S3Presigner

[Link-SonatypeReleases]: https://oss.sonatype.org/content/repositories/releases/com/github/wirthan/s3dsl_2.13/ "Sonatype Releases"
[Badge-SonatypeReleases]: https://img.shields.io/nexus/r/https/oss.sonatype.org/com.github.wirthan/s3dsl_2.13.svg "Sonatype Releases"
