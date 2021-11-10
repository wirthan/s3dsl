organization := "com.github.wirthan"

name := "s3dsl"

val scala2_13 = "2.13.6"
val scala2 = List(scala2_13)

scalaVersion := scala2_13
ThisBuild / crossScalaVersions := scala2

val catsVersion       = "2.6.1"
val catsEffectVersion = "2.5.1"
val mouseVersion      = "1.0.4"
val circeVersion      = "0.14.1"
val fs2Version        = "2.5.9"
val refinedVersion    = "0.9.26"
val enumeratumVersion = "1.7.0"
val specs2Version     = "4.11.0"

val newtype    = "io.estatico"  %% "newtype"         % "0.4.4"
val enumeratum = "com.beachape" %% "enumeratum"      % enumeratumVersion
val awsS3      = "com.amazonaws"%  "aws-java-sdk-s3" % "1.12.105"
val jaxbApi    = "javax.xml" % "jaxb-api" % "2.1"
val collectionsCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.5.0"

val refined = Seq(
  "eu.timepit" %% "refined",
  "eu.timepit" %% "refined-cats"
).map(_ % refinedVersion)

val fs2 = Seq(
  "co.fs2" %% "fs2-core",
  "co.fs2" %% "fs2-io"
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
  "io.chrisdavenport"          %% "cats-scalacheck"           % "0.3.0",
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
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n <= 12 =>
        List(compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full))
      case _                       => Nil
    }
  },
  Compile / scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n <= 12 => List("-Ypartial-unification", "-Yno-adapted-args", "-Ywarn-unused-import")
      case _                       => List("-Ymacro-annotations")
    }
  },
  wartremoverWarnings in (Compile, compile) ++= Warts.allBut(Wart.Any, Wart.Nothing, Wart.PublicInference),
  wartremoverWarnings in (Test, test) ++= wartsInTest,

  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
)

lazy val s3dsl = project.in(file("."))
  .configs(IntegrationTest)
  .settings(projectSettings)
  .settings(
    Defaults.itSettings,
    libraryDependencies ++= Seq(awsS3, newtype, enumeratum, jaxbApi, collectionsCompat) ++ cats ++ circe ++ fs2 ++ refined ++ testDeps
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
