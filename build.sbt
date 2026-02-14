import xerial.sbt.Sonatype._

val scala3Version = "3.3.7"

val avocetVersion = "1.0.0-SNAPSHOT"

val akkaVersion     = "2.6.19"
val akkaHttpVersion = "10.2.9"

val pekkoVersion     = "1.4.0"
val pekkoHttpVersion = "1.3.0"

val circeVersion = "0.14.1"
val ce2Version   = "2.5.5"
val ce3Version   = "3.3.12"

val zioVersion     = "1.0.18"
val zio2Version    = "2.1.24"
val zioHttpVersion = "3.8.0"

val fs2ce2Version = "2.5.11"
val fs2ce3Version = "3.2.8"
val monixVersion  = "3.4.1"
val scodecVersion = "1.1.34"

scalaVersion := scala3Version

def releaseVersion: String = sys.env.getOrElse("RELEASE_VERSION", "")
def isRelease: Boolean     = releaseVersion != ""
val BaseVersion            = "1.19.0"
def publishVersion: String = if (isRelease) releaseVersion else s"$BaseVersion-SNAPSHOT"

ThisBuild / version := publishVersion

val unusedRepo = Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))

// Suppress false-positive lint warnings for keys used by sbt-header and sbt's run task
Global / excludeLintKeys ++= Set(headerLicense, mainClass)


// OSSRH (Nexus 2) is sunset; publishing happens via Sonatype Central.
//
// - SNAPSHOTs: publish directly to the Central Portal snapshots repository.
// - Releases: use sbt-sonatype's *Central* commands (sonatypeCentralUpload/sonatypeCentralRelease),
//   which talk to the OSSRH Staging API Service (a compatibility layer backed by Central).
//
// Docs:
// - https://central.sonatype.org/pages/ossrh-eol/
// - https://central.sonatype.org/publish/publish-portal-snapshots/
val CentralPortalHost           = "central.sonatype.com"
val CentralPortalSnapshotsRepo  = "https://central.sonatype.com/repository/maven-snapshots/"
val CentralNexusRealm           = "Sonatype Nexus Repository Manager"
val OssrhStagingApiHost         = "ossrh-staging-api.central.sonatype.com"
val OssrhStagingApiServiceLocal = "https://ossrh-staging-api.central.sonatype.com/service/local"
val OssrhStagingApiRealm        = "OSSRH Staging API Service"

// Dependency resolution:
// If we depend on SNAPSHOT artifacts (eg avocet during local development), we must add the
// Central Portal snapshots repository as a resolver. Maven Central does NOT host snapshots.
ThisBuild / resolvers ++=
  (if (avocetVersion.endsWith("-SNAPSHOT")) Seq("central-portal-snapshots" at CentralPortalSnapshotsRepo)
   else Nil)

// Publishing credentials (env vars):
// - SONATYPE_CENTRAL_*: Central Portal snapshots repository (central.sonatype.com)
// - SONATYPE_STAGING_*: OSSRH Staging API Service for releases (ossrh-staging-api.central.sonatype.com)
val CentralUserEnvVar = "SONATYPE_CENTRAL_USERNAME"
val CentralPassEnvVar = "SONATYPE_CENTRAL_PASSWORD"
val StagingUserEnvVar = "SONATYPE_STAGING_USERNAME"
val StagingPassEnvVar = "SONATYPE_STAGING_PASSWORD"

def envUserPass(userKey: String, passKey: String): Option[(String, String)] = {
  val user = sys.env.getOrElse(userKey, "")
  val pass = sys.env.getOrElse(passKey, "")
  if (user.nonEmpty && pass.nonEmpty) Some((user, pass)) else None
}

// Publishing credentials:
//
// We support either:
// - ~/.sbt/sonatype_credentials (local; user/pass reused for both endpoints)
// - SONATYPE_CENTRAL_USERNAME / SONATYPE_CENTRAL_PASSWORD (Central Portal snapshots)
// - SONATYPE_STAGING_USERNAME / SONATYPE_STAGING_PASSWORD (release publishing via OSSRH Staging API Service)
def sonatypeCredentials: Seq[Credentials] = {
  val credsFile = Path.userHome / ".sbt" / "sonatype_credentials"
  val userPassFromFile: Option[(String, String)] =
    if (credsFile.exists()) {
      val kv =
        IO.readLines(credsFile)
          .iterator
          .map(_.trim)
          .filter(l => l.nonEmpty && !l.startsWith("#"))
          .flatMap { l =>
            l.split("=", 2) match {
              case Array(k, v) => Some((k.trim.toLowerCase, v.trim))
              case _           => None
            }
          }
          .toMap

      val user = kv.get("user").orElse(kv.get("username")).getOrElse("")
      val pass = kv.getOrElse("password", "")

      if (user.nonEmpty && pass.nonEmpty) Some((user, pass)) else None
    } else None

  val centralUserPass: Option[(String, String)] =
    envUserPass(CentralUserEnvVar, CentralPassEnvVar).orElse(userPassFromFile)

  val stagingUserPass: Option[(String, String)] =
    envUserPass(StagingUserEnvVar, StagingPassEnvVar).orElse(userPassFromFile)

  val creds = Seq.newBuilder[Credentials]

  centralUserPass.foreach { case (user, pass) =>
    // Central Portal snapshots (for -SNAPSHOT versions)
    creds += Credentials(CentralNexusRealm, CentralPortalHost, user, pass)
  }

  stagingUserPass.foreach { case (user, pass) =>
    // OSSRH Staging API Service (for releases; backed by Central)
    creds += Credentials(OssrhStagingApiRealm, OssrhStagingApiHost, user, pass)
  }

  creds.result()
}

// IMPORTANT:
// The release CI runs `sbt sonatypeCentralRelease` from the root aggregator project.
// Root does NOT use `publishSettings`, so we must wire Sonatype settings at the build level too.
ThisBuild / credentials ++= sonatypeCredentials
ThisBuild / sonatypeCredentialHost := CentralPortalHost
ThisBuild / sonatypeRepository     := OssrhStagingApiServiceLocal
ThisBuild / sonatypeProfileName    := "com.natural-transformation"
ThisBuild / sonatypeProjectHosting := Some(GitHubHosting("natural-transformation", "spoonbill", "zli@natural-transformation.com"))

val dontPublishSettings = Seq(
  publish         := {},
  publishTo       := unusedRepo,
  publishArtifact := false,
  headerLicense   := None
)

val publishSettings = Seq(
  publishMavenStyle      := true,
  Test / publishArtifact := false,
  pomIncludeRepository   := { _ => false },
  // Credentials are configured at `ThisBuild` scope above so `sonatypeCentralRelease` works
  // even when invoked from the root aggregator project.
  // For snapshots, publish directly to the Central Portal snapshots repository.
  // For releases, keep using sbt-sonatype's bundle flow (which uses the staging API host below).
  publishTo := {
    if (version.value.endsWith("-SNAPSHOT")) Some("central-portal-snapshots" at CentralPortalSnapshotsRepo)
    else sonatypePublishToBundle.value
  },
  // sbt-sonatype Central commands use the OSSRH Staging API Service (backed by Central).
  // sonatypeCentral* commands require the credential host to be `central.sonatype.com`.
  sonatypeCredentialHost := CentralPortalHost,
  sonatypeRepository     := OssrhStagingApiServiceLocal,
  sonatypeProfileName    := "com.natural-transformation",
  sonatypeProjectHosting := Some(GitHubHosting("natural-transformation", "spoonbill", "zli@natural-transformation.com")),
  headerLicense := Some(HeaderLicense.ALv2("2024", "Natural Transformation BV")),
  licenses      := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
)

val commonSettings = publishSettings ++ Seq(
  organization := "com.natural-transformation",
  scalaVersion       := scala3Version,
  version            := publishVersion,
  scalafmtOnCompile  := false,
  libraryDependencies ++= Seq(
    "org.scalatest"     %% "scalatest"       % "3.2.9"   % Test,
    "org.scalatestplus" %% "scalacheck-1-15" % "3.2.9.0" % Test
  ),
  //javaOptions in Test += "-XX:-OmitStackTraceInFastThrow",
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-language:postfixOps",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-Ykind-projector"
  )
)

val exampleSettings = commonSettings ++ dontPublishSettings ++ Seq(
  libraryDependencies += "org.slf4j" % "slf4j-simple" % "2.0.9"
)

val modules  = file("modules")
val interop  = file("interop")
val examples = file("examples")
val misc     = file("misc")

lazy val bytes = project
  .in(modules / "bytes")

  .settings(commonSettings: _*)
  .settings(
    normalizedName := "spoonbill-bytes"
  )

lazy val effect = project
  .in(modules / "effect")

  .settings(commonSettings: _*)
  .settings(
    normalizedName := "spoonbill-effect"
  )
  .dependsOn(bytes)

lazy val web = project
  .in(modules / "web")

  .settings(commonSettings: _*)
  .settings(
    description    := "Collection of data classes for Web Standards support",
    normalizedName := "spoonbill-web"
  )

lazy val http = project
  .in(modules / "http")

  .settings(commonSettings: _*)
  .settings(
    normalizedName := "spoonbill-http",
    libraryDependencies ++= Seq(
      ("com.typesafe.akka" %% "akka-actor-typed" % akkaVersion     % Test).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-stream"      % akkaVersion     % Test).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-http"        % akkaHttpVersion % Test).cross(CrossVersion.for3Use2_13)
    )
  )
  .dependsOn(effect, web)

lazy val webDsl = project
  .in(modules / "web-dsl")

  .settings(commonSettings: _*)
  .settings(
    description    := "Convenient DSL for web purposes",
    normalizedName := "spoonbill-web-dsl"
  )
  .dependsOn(effect, web)

lazy val spoonbill = project
  .in(modules / "spoonbill")

  .settings(commonSettings: _*)
  .settings(
    normalizedName := "spoonbill",
    libraryDependencies ++= Seq(
      "com.natural-transformation" %% "avocet-core"   % avocetVersion,
      "com.natural-transformation" %% "avocet-events" % avocetVersion
    ),
    Compile / resourceGenerators += Def.task {
      val source = baseDirectory.value / "src" / "main" / "es6"
      val target = (Compile / resourceManaged).value / "static"
      val log    = streams.value.log
      JsUtils.assembleJs(source, target, log)
    }.taskValue
  )
  .dependsOn(effect, web)

lazy val standalone = project
  .in(modules / "standalone")

  .settings(commonSettings: _*)
  .settings(
    normalizedName := "spoonbill-standalone"
  )
  .dependsOn(spoonbill, http)

lazy val testkit = project
  .in(modules / "testkit")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                         := "spoonbill-testkit",
    libraryDependencies += "org.graalvm.js" % "js" % "20.3.0"
  )
  .dependsOn(spoonbill)

// Interop

lazy val akka = project
  .in(interop / "akka")

  .settings(commonSettings: _*)
  .settings(
    normalizedName := "spoonbill-akka",
    libraryDependencies ++= Seq(
      ("com.typesafe.akka" %% "akka-actor"  % akkaVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-stream" % akkaVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-http"   % akkaHttpVersion).cross(CrossVersion.for3Use2_13)
    )
  )
  .dependsOn(spoonbill)

lazy val pekko = project
  .in(interop / "pekko")

  .settings(commonSettings: _*)
  .settings(
    normalizedName := "spoonbill-pekko",
    libraryDependencies ++= Seq(
      ("org.apache.pekko" %% "pekko-actor"          % pekkoVersion).cross(CrossVersion.for3Use2_13),
      ("org.apache.pekko" %% "pekko-stream"         % pekkoVersion).cross(CrossVersion.for3Use2_13),
      ("org.apache.pekko" %% "pekko-http"           % pekkoHttpVersion).cross(CrossVersion.for3Use2_13),
      ("org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion     % Test).cross(CrossVersion.for3Use2_13),
      ("org.apache.pekko" %% "pekko-http-testkit"   % pekkoHttpVersion % Test).cross(CrossVersion.for3Use2_13)
    )
  )
  .dependsOn(spoonbill)

lazy val zioHttp = project
  .in(interop / "zio-http")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                   := "spoonbill-zio-http",
    libraryDependencies += "dev.zio" %% "zio-http" % zioHttpVersion
  )
  .dependsOn(spoonbill, web, zio2, zio2Streams)

lazy val slf4j = project
  .in(interop / "slf4j")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                    := "spoonbill-slf4j",
    libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.25"
  )
  .dependsOn(effect)

lazy val ce2 = project
  .in(interop / "ce2")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                         := "spoonbill-ce2",
    libraryDependencies += "org.typelevel" %% "cats-effect" % ce2Version
  )
  .dependsOn(effect)

lazy val ce3 = project
  .in(interop / "ce3")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                         := "spoonbill-ce3",
    libraryDependencies += "org.typelevel" %% "cats-effect" % ce3Version
  )
  .dependsOn(effect)

lazy val monix = project
  .in(interop / "monix")

  .settings(commonSettings: _*)
  .settings(
    normalizedName := "spoonbill-monix",
    libraryDependencies ++= List(
      "io.monix" %% "monix-eval"      % monixVersion,
      "io.monix" %% "monix-execution" % monixVersion
    )
  )
  .dependsOn(effect)

lazy val zio = project
  .in(interop / "zio")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                   := "spoonbill-zio",
    libraryDependencies += "dev.zio" %% "zio" % zioVersion
  )
  .dependsOn(effect)

lazy val zio2 = project
  .in(interop / "zio2")

  .settings(commonSettings: _*)
  .settings(
    normalizedName := "spoonbill-zio2",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"               % zio2Version,
      "dev.zio" %% "zio-test"          % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt"      % zio2Version % Test,
      "dev.zio" %% "zio-test-magnolia" % zio2Version % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(effect)

lazy val zioStreams = project
  .in(interop / "zio-streams")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                   := "spoonbill-zio-streams",
    libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion
  )
  .dependsOn(effect, zio)

lazy val zio2Streams = project
  .in(interop / "zio2-streams")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                   := "spoonbill-zio2-streams",
    libraryDependencies += "dev.zio" %% "zio-streams" % zio2Version
  )
  .dependsOn(effect, zio2)

lazy val fs2ce2 = project
  .in(interop / "fs2-ce2")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                  := "spoonbill-fs2-ce2",
    libraryDependencies += "co.fs2" %% "fs2-core" % fs2ce2Version
  )
  .dependsOn(effect, ce2)

lazy val fs2ce3 = project
  .in(interop / "fs2-ce3")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                  := "spoonbill-fs2-ce3",
    libraryDependencies += "co.fs2" %% "fs2-core" % fs2ce3Version
  )
  .dependsOn(effect, ce3)

lazy val scodec = project
  .in(interop / "scodec")

  .settings(commonSettings: _*)
  .settings(
    normalizedName                      := "spoonbill-scodec",
    libraryDependencies += "org.scodec" %% "scodec-bits" % scodecVersion
  )
  .dependsOn(bytes)

// Examples

lazy val simpleExample = (project in examples / "simple")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("SimpleExample"))
  .dependsOn(akka)

lazy val routingExample = project
  .in(examples / "routing")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("RoutingExample"))
  .dependsOn(akka)

lazy val gameOfLifeExample = project
  .in(examples / "game-of-life")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("GameOfLife"))
  .dependsOn(akka)

lazy val formDataExample = project
  .in(examples / "form-data")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("FormDataExample"))
  .dependsOn(akka)

lazy val `file-streaming-example` = project
  .in(examples / "file-streaming")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("FileStreamingExample"))
  .dependsOn(akka, monix)

lazy val delayExample = project
  .in(examples / "delay")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("DelayExample"))
  .dependsOn(akka)

lazy val focusExample = project
  .in(examples / "focus")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("FocusExample"))
  .dependsOn(akka)

lazy val webComponentExample = project
  .in(examples / "web-component")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("WebComponentExample"))
  .dependsOn(akka)

lazy val componentExample = project
  .in(examples / "component")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("ComponentExample"))
  .dependsOn(akka)

lazy val akkaHttpExample = project
  .in(examples / "akka-http")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("AkkaHttpExample"))
  .dependsOn(akka)

lazy val pekkoHttpExample = project
  .in(examples / "pekko-http")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("PekkoHttpExample"))
  .dependsOn(pekko)

lazy val zioHttpExample = project
  .in(examples / "zio-http")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("ZioHttpExample"))
  .dependsOn(zio, zioHttp)

lazy val catsEffectExample = project
  .in(examples / "cats")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("CatsIOExample"))
  .dependsOn(ce3, akka)

lazy val zioExample = project
  .in(examples / "zio")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("ZioExample"))
  .dependsOn(zio2, standalone, testkit % Test)

lazy val monixExample = project
  .in(examples / "monix")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("MonixExample"))
  .dependsOn(monix, akka)

lazy val eventDataExample = project
  .in(examples / "event-data")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("EventDataExample"))
  .dependsOn(akka)

lazy val evalJsExample = project
  .in(examples / "evalJs")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("EvalJsExample"))
  .dependsOn(akka)

lazy val contextScopeExample = project
  .in(examples / "context-scope")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("ContextScopeExample"))
  .dependsOn(akka)

lazy val extensionExample = project
  .in(examples / "extension")
  .disablePlugins(HeaderPlugin)
  .settings(exampleSettings: _*)
  .settings(mainClass := Some("ExtensionExample"))
  .dependsOn(akka)

// Misc

lazy val `integration-tests` = project
  .in(misc / "integration-tests")
  .disablePlugins(HeaderPlugin)
  .settings(commonSettings)
  .settings(dontPublishSettings: _*)
  .settings(
    run / fork := true,
    libraryDependencies ++= Seq(
      "org.slf4j"               % "slf4j-simple"  % "2.0.9",
      "org.seleniumhq.selenium" % "selenium-java" % "4.12.1",
      "io.circe"               %% "circe-core"    % circeVersion,
      "io.circe"               %% "circe-generic" % circeVersion,
      "io.circe"               %% "circe-parser"  % circeVersion
    )
  )
  .dependsOn(slf4j)
  .dependsOn(akka)

lazy val `performance-benchmark` = project
  .in(misc / "performance-benchmark")
  .disablePlugins(HeaderPlugin)
  .settings(commonSettings)
  .settings(dontPublishSettings: _*)
  .settings(
    run / fork := true,
    libraryDependencies ++= Seq(
      ("com.typesafe.akka" %% "akka-http"        % akkaHttpVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-stream"      % akkaVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-actor"       % akkaVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-actor-typed" % akkaVersion).cross(CrossVersion.for3Use2_13),
      "com.lihaoyi"        %% "ujson"            % "1.3.15"
    )
  )
  .dependsOn(spoonbill)

lazy val root = project
  .in(file("."))
  .disablePlugins(HeaderPlugin)
  .settings(dontPublishSettings: _*)
  .settings(name := "Spoonbill Project")
  .aggregate(
    spoonbill,
    effect,
    web,
    http,
    standalone,
    testkit,
    bytes,
    webDsl,
    // Interop
    akka,
    pekko,
    ce2,
    ce3,
    monix,
    zio,
    zioStreams,
    zio2,
    zio2Streams,
    slf4j,
    scodec,
    fs2ce2,
    fs2ce3,
    zioHttp,
    // Examples
    simpleExample,
    routingExample,
    gameOfLifeExample,
    formDataExample,
    `file-streaming-example`,
    delayExample,
    focusExample,
    webComponentExample,
    componentExample,
    akkaHttpExample,
    contextScopeExample,
    eventDataExample,
    extensionExample,
    zioExample,
    monixExample,
    catsEffectExample,
    evalJsExample,
    zioHttpExample
  )
