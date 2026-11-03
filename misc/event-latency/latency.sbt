// End-to-end click latency harness. The same counter application is compiled
// against Spoonbill 1.1.1, the released 8.0.1, and the repository working tree.
// See README.md; prepare.sh writes target/local-classpath.txt first.
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature")

val plainSources   = file("shared/plain").getAbsoluteFile
val guardedSources = file("guarded").getAbsoluteFile
// Working-tree-only sources: the concurrent-checks opt-in does not exist in 8.0.1.
val localOnlySources = file("local-only").getAbsoluteFile
val jdbcDrivers = Seq(
  "org.postgresql" % "postgresql" % "42.7.13",
  "com.zaxxer"     % "HikariCP"   % "5.1.0"
)

def variant(sources: File*) = Seq(Compile / unmanagedSourceDirectories ++= sources)

lazy val v1 = project
  .in(file("target/v1"))
  .settings(variant(plainSources): _*)
  .settings(libraryDependencies += "com.natural-transformation" %% "spoonbill-pekko" % "1.1.1")

lazy val v8 = project
  .in(file("target/v8"))
  .settings(variant(plainSources, guardedSources): _*)
  .settings(
    libraryDependencies ++= Seq(
      "com.natural-transformation" %% "spoonbill-pekko"         % "8.0.1",
      "com.natural-transformation" %% "spoonbill-security-jdbc" % "8.0.1"
    ) ++ jdbcDrivers
  )

// Repository classes and their dependency jars, exported by prepare.sh.
lazy val local = project
  .in(file("target/local"))
  .settings(variant(plainSources, guardedSources, localOnlySources): _*)
  .settings(
    Compile / unmanagedClasspath ++= {
      val listing = file("target/local-classpath.txt").getAbsoluteFile
      IO.read(listing).trim.split(java.io.File.pathSeparator).toSeq.distinct.map(path => Attributed.blank(file(path)))
    },
    Runtime / unmanagedClasspath ++= (Compile / unmanagedClasspath).value,
    libraryDependencies ++= jdbcDrivers
  )
