import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import ReleaseTransformations._

import scala.language.postfixOps
import sbt.{Credentials, file, _}

lazy val username = "openlaw"
lazy val repo     = "openlaw-core"

licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))

lazy val scalaV = "2.12.7"
lazy val catsV = "1.4.0"
lazy val parboiledV = "2.1.5"
lazy val circeV = "0.10.1"
lazy val akkaV = "2.5.17"

lazy val repositories = Seq(
  Resolver.jcenterRepo,
  "central" at "http://central.maven.org/maven2/",
  "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases",
  "ethereumj repository" at "http://dl.bintray.com/ethereum/maven",
  "maven central" at "https://mvnrepository.com/repos/central",
  "jitpack.io" at "https://jitpack.io",
  Resolver.mavenLocal
)

scalacOptions ++= Seq("-Xlog-implicits", "-unchecked", "-deprecation", "-feature")
javacOptions ++= Seq("-Xms512M", "-Xmx1024M", "-Xss1M", "-XX:+CMSClassUnloadingEnabled")

lazy val commonSettings = Seq(
  organization := "org.openlaw",
  name := "openlaw-core",
  scalaVersion := scalaV,
  wartremoverErrors ++= rules
)

lazy val publishSettings = Seq(
  homepage := Some(url(s"https://github.com/$username/$repo")),
  licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0")),
  bintrayReleaseOnPublish in ThisBuild := true,
  bintrayOrganization := Some("openlaw"),
  bintrayRepository := "openlaw-core",
  bintrayPackageLabels := Seq("openlaw-core"),
  scmInfo := Some(ScmInfo(url(s"https://github.com/$username/$repo"), s"git@github.com:$username/$repo.git")),
  releaseCrossBuild := true,
  developers := List(
    Developer(
      id = username,
      name = "David Roon",
      email = "david.roon@consensys.net",
      url = new URL(s"http://github.com/adridadou")
    ),
    Developer(
      id = username,
      name = "Jacqueline Outka",
      email = "jacqueline@outka.xyz",
      url = new URL(s"http://github.com/$username")
    )
  ),
  publishTo in ThisBuild := Some("Bintray" at "https://api.bintray.com/maven/openlaw/maven/openlaw-core"),
  credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
)

lazy val releaseSettings = releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,              // : ReleaseStep
  inquireVersions,                        // : ReleaseStep
  setReleaseVersion,                      // : ReleaseStep
  commitReleaseVersion,                   // : ReleaseStep, performs the initial git checks
  tagRelease,                             // : ReleaseStep
  publishArtifacts,                       // : ReleaseStep,
  setNextVersion,                         // : ReleaseStep
  commitNextVersion,                      // : ReleaseStep
  pushChanges                             // : ReleaseStep, also checks that an upstream branch is properly configured
)

val rules = Seq(Wart.ArrayEquals, Wart.OptionPartial, Wart.EitherProjectionPartial, Wart.Enumeration, Wart.ExplicitImplicitTypes, Wart.FinalVal, Wart.JavaConversions, Wart.JavaSerializable, Wart.LeakingSealed)

lazy val openlawCoreJs = (project in file("openlawCoreJs")).settings(
  scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule)},
  organization := "org.openlaw",
  name := "openlaw-core-client",
  scalaVersion := scalaV,
  wartremoverErrors ++= rules,
  libraryDependencies ++= Seq(
    "org.scala-js"  %%% "scalajs-dom"             % "0.9.6",
  ),
  relativeSourceMaps := true,
  artifactPath in (Compile, fastOptJS) := crossTarget.value / "client.js",
  artifactPath in (Compile, fullOptJS) := crossTarget.value / "client.js"
).enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedJs)

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Pure) // [Pure, Full, Dummy], default: CrossType.Full
  .in(file("shared"))
  .jvmSettings(
    libraryDependencies ++= Seq(
      "io.circe"                %% "circe-iteratee"      % "0.11.0",
      "io.iteratee"             %% "iteratee-monix"      % "0.18.0",
      "io.monix"                %% "monix-eval"          % "3.0.0-fbcb270",
      "io.monix"                %% "monix-execution"     % "3.0.0-fbcb270",
      "io.circe"                %% "circe-core"          % circeV,
      "io.circe"                %% "circe-generic"       % circeV,
      "io.circe"                %% "circe-parser"        % circeV,
      "com.typesafe.play"       %% "play-json"           % "2.6.10",
      "org.parboiled"           %% "parboiled"           % parboiledV,
      "org.typelevel"           %% "cats-core"           % catsV,
      "org.typelevel"           %% "cats-free"           % catsV,
      "io.github.cquiroz"       %% "scala-java-time"     % "2.0.0-M13",
      "biz.enef"                %% "slogging-slf4j"      % "0.6.1",
      //Test
      "org.scalacheck"          %% "scalacheck"          % "1.14.0"       % Test,
      "org.scalatest"           %% "scalatest"           % "3.0.6-SNAP2"  % Test,
    )
  ).jsSettings(
    libraryDependencies ++= Seq(
      "io.github.cquiroz"       %%% "scala-java-time"      % "2.0.0-M13",
      "io.github.cquiroz"       %%% "scala-java-time-tzdb" % "2.0.0-M13_2018c",
      "org.parboiled"           %%% "parboiled"            % parboiledV,
      "com.chuusai"             %%% "shapeless"            % "2.3.3",
      "biz.enef"                %%% "slogging"             % "0.6.1",
      "org.typelevel"           %%% "cats-core"            % catsV,
      "org.typelevel"           %%% "cats-free"            % catsV,
      "io.circe"                %%% "circe-core"           % circeV,
      "io.circe"                %%% "circe-generic"        % circeV,
      "io.circe"                %%% "circe-parser"         % circeV,
      "com.typesafe.play"       %%% "play-json"            % "2.6.10",
      //Test
      "org.scalacheck"          %%% "scalacheck"          % "1.14.0"       % Test,
      "org.scalatest"           %%% "scalatest"           % "3.0.6-SNAP2"  % Test
    )
  )
  .settings(commonSettings: _*)
  .settings(publishSettings: _*)
  .settings(releaseSettings: _*)
  .enablePlugins(WartRemover)

lazy val sharedJvm = shared.jvm
lazy val sharedJs = shared.js


val root = (project in file("."))
  .dependsOn(sharedJvm, sharedJs)
  .aggregate(sharedJvm, sharedJs)

