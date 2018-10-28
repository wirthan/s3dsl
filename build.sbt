version := "0.1"


val catsVersion       = "1.4.0"
val catsEffectVersion = "1.0.0"
val mouseVersion      = "0.18"
val fs2Version        = "1.0.0"
val refinedVersion    = "0.9.2"
val specs2Version     = "4.3.4"

val newtype = "io.estatico"  %% "newtype"         % "0.4.2"
val awsS3   = "com.amazonaws"%  "aws-java-sdk-s3" % "1.11.283"

val refined = Seq(
  "eu.timepit" %% "refined"            % refinedVersion,
  "eu.timepit" %% "refined-cats"       % refinedVersion
)

val fs2 = Seq(
  "co.fs2" %% "fs2-core" % fs2Version,
  "co.fs2" %% "fs2-io"   % fs2Version
)

val cats = Seq(
  "org.typelevel" %% "cats-core"   % catsVersion,
  "org.typelevel" %% "cats-effect" % catsEffectVersion,
  "org.typelevel" %% "mouse"       % mouseVersion,
)

val testDeps = Seq(
  "org.specs2" %% "specs2-core"          % specs2Version,
  "org.specs2" %% "specs2-matcher-extra" % specs2Version,
  "org.specs2" %% "specs2-scalacheck"    % specs2Version,
  "org.specs2" %% "specs2-cats"          % specs2Version,
  "eu.timepit" %% "refined-scalacheck"   % refinedVersion
).map(_ % "test,it")

lazy val wartsInTest = Warts.allBut(
  Wart.Any,
  Wart.Nothing,
  Wart.NonUnitStatements,
  Wart.PublicInference,
  Wart.NonUnitStatements
)

lazy val projectSettings = Seq(
  name := "s3dsl",
  scalaVersion := "2.12.7",
  scalacOptions ++= Seq(
    "-target:jvm-1.8",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-deprecation",
    "-feature",
    "-Xlint",
    "-Ypartial-unification",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused",
    "-Ywarn-unused-import",
    //"-Xfatal-warnings"
  ),
  wartremoverWarnings in (Compile, compile) ++= Warts.allBut(Wart.Any, Wart.Nothing, Wart.PublicInference),
  wartremoverWarnings in (Test, test) ++= wartsInTest,

  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.4"),
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.7"),
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
)

lazy val s3dsl = project.in(file("."))
  .configs(IntegrationTest)
  .settings(projectSettings)
  .settings(
    Defaults.itSettings,
    libraryDependencies ++= Seq(awsS3, newtype) ++ cats ++ fs2 ++ refined ++ testDeps
  )