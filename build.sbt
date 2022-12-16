organization := "com.github.wirthan"

name := "s3dsl"

val scala2_13 = "2.13.9"
val scala2 = List(scala2_13)

scalaVersion := scala2_13
ThisBuild / crossScalaVersions := scala2

val catsVersion       = "2.9.0"
val catsEffectVersion = "3.4.2"
val mouseVersion      = "1.2.1"
val circeVersion      = "0.14.3"
val fs2Version        = "3.4.0"
val refinedVersion    = "0.10.1"
val enumeratumVersion = "1.7.2"
val specs2Version     = "4.19.0"

val newtype    = "io.estatico"  %% "newtype"         % "0.4.4"
val enumeratum = "com.beachape" %% "enumeratum"      % enumeratumVersion
val awsS3      = "software.amazon.awssdk" % "s3" % "2.18.40"
val awsS3TransferManager = "software.amazon.awssdk" % "s3-transfer-manager" % "2.18.40-PREVIEW"
val jaxbApi    = "javax.xml" % "jaxb-api" % "2.1"
val collectionsCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.9.0"


val refined = Seq(
  "eu.timepit" %% "refined",
  "eu.timepit" %% "refined-cats"
).map(_ % refinedVersion)

val fs2 = Seq(
  "co.fs2" %% "fs2-core",
  "co.fs2" %% "fs2-io",
  "co.fs2" %% "fs2-reactive-streams",
).map(_ % fs2Version)

val cats = Seq(
  "org.typelevel" %% "cats-core"   % catsVersion,
  "org.typelevel" %% "cats-effect" % catsEffectVersion,
  "org.typelevel" %% "mouse"       % mouseVersion,
)

val circe = Seq(
  "io.circe"     %% "circe-core"           % circeVersion,
  "io.circe"     %% "circe-generic"        % circeVersion,
  "io.circe"     %% "circe-generic-extras" % circeVersion,
  "io.circe"     %% "circe-parser"         % circeVersion,
  "io.circe"     %% "circe-refined"        % circeVersion,
  "com.beachape" %% "enumeratum-circe"     % enumeratumVersion,
)

val testDeps = Seq(
  "org.specs2"                 %% "specs2-core"               % specs2Version,
  "org.specs2"                 %% "specs2-scalacheck"         % specs2Version,
  "org.specs2"                 %% "specs2-cats"               % specs2Version,
  "com.github.alexarchambault" %% "scalacheck-shapeless_1.15" % "1.3.0",
  "eu.timepit"                 %% "refined-scalacheck"        % refinedVersion,
  "io.chrisdavenport"          %% "cats-scalacheck"           % "0.3.2",
  "com.beachape"               %% "enumeratum-scalacheck"     % enumeratumVersion,
  "io.circe"                   %% "circe-literal"             % circeVersion
).map(_ % "test,it")

lazy val wartsInTest = Warts.allBut(
  Wart.Any,
  Wart.Nothing,
  Wart.NonUnitStatements,
  Wart.PublicInference,
  Wart.NonUnitStatements
)

lazy val projectSettings = Seq(
  crossScalaVersions := scala2,
  scalacOptions ++= Seq(
    "-language:higherKinds",
    "-language:implicitConversions",
    "-deprecation",
    "-feature",
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused",
    //"-Xfatal-warnings"
  ),
  Compile / scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n <= 13 => List("-Ymacro-annotations")
      case _                       => Nil
    }
  },
  Compile / compile / wartremoverWarnings  ++= Warts.allBut(Wart.Any, Wart.Nothing, Wart.PublicInference),
  Test / test / wartremoverWarnings  ++= wartsInTest,

  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
)

lazy val s3dsl = project.in(file("."))
  .configs(IntegrationTest)
  .settings(projectSettings)
  .settings(
    Defaults.itSettings,
    libraryDependencies ++= Seq(awsS3, awsS3TransferManager, newtype, enumeratum, jaxbApi, collectionsCompat) ++ cats ++ circe ++ fs2 ++ refined ++ testDeps
  )

//
// Release
//

releasePublishArtifactsAction := PgpKeys.publishSigned.value

releaseProcess := {
  import ReleaseTransformations._
  releaseProcess.value.dropRight(1) ++ Seq[ReleaseStep](
    releaseStepCommand("sonatypeRelease"),
    pushChanges
  )
}

//
// Publishing
//

homepage := Some(url("https://github.com/mmz-srf/scala-xml-codec"))

scmInfo := Some(ScmInfo(
  url("https://github.com/wirthan/s3dsl"), "git@github.com:wirthan/s3dsl.git")
)

developers := List(
  Developer(id = "wirthan", name="Andreas Wirth", email="andreas.wirth78@gmail.com", url = url("https://github.com/wirthan"))
)

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

publishMavenStyle := true

publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)

publishConfiguration := publishConfiguration.value.withOverwrite(true)
