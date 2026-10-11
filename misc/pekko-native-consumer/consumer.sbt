scalaVersion := sys.props.getOrElse("spoonbill.scala.version", "3.3.8")

val spoonbillVersion = sys.props.getOrElse("spoonbill.version", "5.0.0-SNAPSHOT")
val pekkoVersion = sys.props.getOrElse("pekko.version", "1.7.0")
val pekkoHttpVersion = sys.props.getOrElse("pekko.http.version", "1.4.0")

libraryDependencies ++= Seq(
  "com.natural-transformation" %% "spoonbill-pekko" % spoonbillVersion,
  "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence" % pekkoVersion,
  "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion
)

lazy val verifyNativeGraph = taskKey[Unit]("Reject mixed Scala 2.13/3 Pekko artifacts in the downstream consumer")
verifyNativeGraph := {
  val modules = update.value.allModules.filter(_.organization == "org.apache.pekko")
  require(modules.nonEmpty, "The downstream fixture did not resolve Pekko")
  require(modules.forall(_.name.endsWith("_3")), s"Non-native Pekko artifacts: $modules")
  for (name <- Seq("pekko-actor_3", "pekko-actor-typed_3", "pekko-stream_3", "pekko-persistence_3", "pekko-http_3"))
    require(modules.exists(_.name == name), s"Missing downstream module $name")
  streams.value.log.info(modules.map(module => s"${module.name}:${module.revision}").sorted.mkString("Native Pekko graph: ", ", ", ""))
}

Compile / run / fork := true
Compile / mainClass := Some("NativePekkoConsumer")
