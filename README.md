# s3dsl

[![Release Artifacts][Badge-SonatypeReleases]][Link-SonatypeReleases]

Minimal, stream based S3 DSL. Depends on [aws-sdk-java-v2](https://github.com/aws/aws-sdk-java-v2).

## Getting s3dsl
If you're using SBT, add the following line to your build file:

    libraryDependencies += "com.github.wirthan" %% "s3dsl" % <version>

## Features
 - Final tagless DSL for basic operations on S3 buckets and objects
 - Scala wrapper around S3Presigner

## Releasing

### Prerequisites
 - Sonatype Central user token in `~/.sbt/1.0/sonatype.sbt`:

       credentials += Credentials("Sonatype Nexus Repository Manager", "central.sonatype.com", "<user>", "<token>")

 - A PGP secret key usable by [sbt-pgp](https://github.com/sbt/sbt-pgp) (`gpg --list-secret-keys`).

### Dry run

    export GPG_TTY=$(tty)
    sbt releaseDryRun

Runs the unit tests and builds the signed artifacts for all Scala versions into the local bundle
directory `target/sonatype-staging/<version>`, using the release version (without `-SNAPSHOT`)
for the sbt session only. Nothing else happens: `version.sbt` is not changed,
nothing is committed, tagged or pushed, and nothing is uploaded to Sonatype. Use it to check tests,
PGP signing and the generated POMs before a real release.

### Release

    export GPG_TTY=$(tty)
    sbt "release cross with-defaults"

[Link-SonatypeReleases]: https://central.sonatype.com/artifact/com.github.wirthan/s3dsl_3 "Sonatype Releases"
[Badge-SonatypeReleases]: https://img.shields.io/maven-central/v/com.github.wirthan/s3dsl_3.svg "Sonatype Releases"
