scalaVersion := sys.props.getOrElse("spoonbill.scala.version", "3.3.8")

val spoonbillVersion = sys.props.getOrElse("spoonbill.version", "8.0.0-SNAPSHOT")

// The adapter depends on the public recovery API only; the conformance kit is
// a test dependency. Nothing here uses dependsOn, repository classes, JDBC or
// private Spoonbill internals.
libraryDependencies ++= Seq(
  "com.natural-transformation" %% "spoonbill"                  % spoonbillVersion,
  "com.natural-transformation" %% "spoonbill-recovery-testkit" % spoonbillVersion % Test
)

lazy val verifyKitGraph = taskKey[Unit]("Reject JDBC or private module dependencies in the adapter's resolved graph")
verifyKitGraph := {
  val modules = update.value.allModules
  require(modules.exists(_.name == "spoonbill-recovery-testkit_3"), "The conformance kit did not resolve")
  require(!modules.exists(module => module.name.contains("security-jdbc") || module.organization == "org.postgresql"),
    s"The payload adapter must not depend on JDBC: ${modules.map(_.name).sorted.mkString(", ")}")
  streams.value.log.info(modules.map(module => s"${module.name}:${module.revision}").sorted.mkString("Adapter graph: ", ", ", ""))
}
