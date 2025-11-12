import scala.collection.Seq
import xerial.sbt.Sonatype.*

organization := "com.github.wirthan"

name := "s3dsl"

val javaVersion = 11
val scala2_13 = "2.13.17"
val scala3 = "3.7.1"
val supportedScalaVersions = List(scala2_13,scala3)

scalaVersion := scala3
javacOptions ++= Seq("-source", javaVersion.toString, "-target", javaVersion.toString)
releaseCrossBuild := true

val catsVersion       = "2.12.0"
val catsEffectVersion = "3.5.4"
val mouseVersion      = "1.3.2"
val circeVersion      = "0.14.7"
val fs2Version        = "3.11.0"
val enumeratumVersion = "1.9.0"
val specs2Version     = "4.19.0"
val enumeratum = "com.beachape" %% "enumeratum"      % enumeratumVersion
val awsS3      = "software.amazon.awssdk" % "s3" % "2.38.3"
val jaxbApi    = "javax.xml" % "jaxb-api" % "2.1"
val collectionsCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0"

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
  "io.circe"     %% "circe-parser"         % circeVersion,
  "com.beachape" %% "enumeratum-circe"     % enumeratumVersion,
)

val testDeps = Seq(
  "org.specs2"                 %% "specs2-core"               % specs2Version,
  "org.specs2"                 %% "specs2-scalacheck"         % specs2Version,
  "org.specs2"                 %% "specs2-cats"               % specs2Version,
  "io.chrisdavenport"          %% "cats-scalacheck"           % "0.3.2",
  "com.beachape"               %% "enumeratum-scalacheck"     % enumeratumVersion,
  "io.circe"                   %% "circe-literal"             % circeVersion
).map(_ % "test,it")

lazy val warts = Warts.allBut(
  Wart.Any,
  Wart.Nothing,
  Wart.NonUnitStatements,
  Wart.PublicInference,
  Wart.NonUnitStatements
)

lazy val sharedScalacOptions =
  Seq(
    "-language:higherKinds",
    "-language:implicitConversions",
    "-deprecation",
    "-feature"
  )

lazy val scala2CompileOptions = sharedScalacOptions ++
  Seq(
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused"
  )

lazy val scala3CompileOptions = sharedScalacOptions ++
  Seq("-Wunused:all", "-Xunchecked-java-output-version:11")


lazy val projectSettings = Seq(
  crossScalaVersions := supportedScalaVersions,
  Compile / scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => scala2CompileOptions
      case Some((3,_))  => scala3CompileOptions
    }
  },
  Compile / compile / wartremoverWarnings  := warts,
  Test / test / wartremoverWarnings  := warts,

)

lazy val IntegrationTest = config("it") extend(Test)

lazy val s3dsl = project.in(file("."))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .settings(projectSettings)
  .settings(
    Defaults.itSettings,
    libraryDependencies ++= Seq(awsS3, enumeratum, jaxbApi, collectionsCompat) ++ cats ++ circe ++ fs2 ++ testDeps
  )

//
// Publishing
//

sonatypeProjectHosting := Some(GitHubHosting("wirthan", "s3dsl", "andreas.wirth78@gmail.com"))
homepage := Some(url("https://github.com/wirthan/s3dsl"))

scmInfo := Some(ScmInfo(
  url("https://github.com/wirthan/s3dsl"), "git@github.com:wirthan/s3dsl.git")
)

developers := List(
  Developer(id = "wirthan", name="Andreas Wirth", email="andreas.wirth78@gmail.com", url = url("https://github.com/wirthan"))
)

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

sonatypeCredentialHost := "central.sonatype.com"
ThisBuild / publishTo := sonatypePublishToBundle.value

publishMavenStyle := true
