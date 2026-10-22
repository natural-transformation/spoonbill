import sbt._
import sbt.Keys._
import java.security.MessageDigest
import scala.xml.{Elem, Node, NodeSeq, XML}
import scala.xml.transform.{RewriteRule, RuleTransformer}

/** A derived, published jar: never rely on duplicate-class classpath order. */
object ZioHttpRuntime {
  val upstreamVersion = "3.11.6"
  val zioVersion = "2.1.27"
  private val artifactName = "zio-http_3"
  private val binarySha = "e5ac833344472a68a017c7d2dec4ce00cc5251bcdea871cb9a9eb7853abe4dc0"
  private val sourcesSha = "149a17e15f1591f8f7e6d576ca5a109859206e9dec60efdaaf36e32dba749e5d"
  private val pomSha = "37ddab087ed7b3bec4777d782e64ce3a083360befc1e5e2661944bdc97c7380c"
  private val handlerSha = "c5b3d608ee58a6dcb355f811e762b1ee5b7f1ac9586728b109329857602cae0e"

  private def digest(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(IO.readBytes(file)).map(b => f"${b & 0xff}%02x").mkString

  private def verify(file: File, expected: String): Unit = {
    val actual = digest(file)
    require(actual == expected, s"Pinned zio-http input changed: ${file.getName}; expected $expected, got $actual")
  }

  private def dependencies(pom: File): Seq[ModuleID] = {
    verify(pom, pomSha)
    (XML.loadFile(pom) \ "dependencies" \ "dependency").flatMap { dependency =>
      def field(name: String): String = (dependency \ name).text.trim
      val scope = field("scope")
      if (scope == "test" || field("artifactId") == "scala3-library_3") Nil
      else {
        val group = field("groupId")
        val artifact = field("artifactId")
        val version = if (group == "dev.zio" && Set("zio_3", "zio-streams_3").contains(artifact)) zioVersion else field("version")
        val module = group % artifact % version
        val scoped = if (scope.isEmpty || scope == "compile") module else module % scope
        val classifier = field("classifier")
        Seq(if (classifier.isEmpty) scoped else scoped.classifier(classifier))
      }
    }
  }

  val settings: Seq[Def.Setting[_]] = Seq(
    normalizedName := "spoonbill-zio-http-runtime",
    description := "ZIO HTTP 3.11.6 with bounded pre-conversion WebSocket ingress",
    libraryDependencies ++= dependencies(baseDirectory.value / "upstream.pom"),
    libraryDependencies += ("dev.zio" % artifactName % upstreamVersion % Provided).artifacts(
      Artifact(artifactName), Artifact(artifactName, "src", "jar", "sources")
    ),
    Compile / exportJars := true,
    Compile / resourceGenerators += Def.task {
      val sourceRoot = (Compile / scalaSource).value
      val names = Seq("WebSocketAppHandler", "WebSocketFrameDeadline", "WebSocketUpgradeBoundary", "SpoonbillWebSocketRuntime")
      val hashes = names.map { name =>
        val path = s"zio/http/netty/$name.scala"
        "\"" + path + "\":\"" + digest(sourceRoot / path) + "\""
      }.mkString(",")
      val manifest = (Compile / resourceManaged).value / "META-INF/spoonbill-zio-http-patch.json"
      IO.write(manifest,
        s"""{"upstreamVersion":"$upstreamVersion","binarySha256":"$binarySha","sourcesSha256":"$sourcesSha","pomSha256":"$pomSha","originalHandlerSha256":"$handlerSha","dependencyOverrides":{"dev.zio:zio_3":"$zioVersion","dev.zio:zio-streams_3":"$zioVersion"},"patchSources":{$hashes}}""")
      Seq(manifest)
    }.taskValue,
    Compile / packageBin / mappings += (baseDirectory.value / "upstream.pom") -> "META-INF/zio-http-upstream.pom",
    Compile / packageBin / mappings ++= {
      val files = update.value.allFiles
      def artifact(suffix: String): File = files.find(_.getName == s"$artifactName-$upstreamVersion$suffix.jar")
        .getOrElse(sys.error(s"Missing pinned zio-http artifact: $suffix"))
      val binary = artifact("")
      val sources = artifact("-sources")
      verify(binary, binarySha)
      verify(sources, sourcesSha)
      val sourceDir = target.value / "verified-upstream-sources"
      IO.unzip(sources, sourceDir)
      verify(sourceDir / "zio/http/netty/WebSocketAppHandler.scala", handlerSha)
      val unpacked = target.value / "verified-upstream-runtime"
      IO.delete(unpacked)
      IO.unzip(binary, unpacked)
      Path.allSubpaths(unpacked).filter { case (_, path) =>
        !path.startsWith("zio/http/netty/WebSocketAppHandler") &&
          path != "META-INF/MANIFEST.MF" &&
          !path.endsWith(".SF") && !path.endsWith(".RSA") && !path.endsWith(".DSA")
      }.toSeq
    },
    Compile / packageSrc / mappings ++= {
      val sources = update.value.allFiles.find(_.getName == s"$artifactName-$upstreamVersion-sources.jar")
        .getOrElse(sys.error("Missing pinned zio-http sources"))
      verify(sources, sourcesSha)
      val unpacked = target.value / "verified-upstream-sources"
      IO.unzip(sources, unpacked)
      Path.allSubpaths(unpacked).filter { case (_, path) =>
        path != "zio/http/netty/WebSocketAppHandler.scala" && path != "META-INF/MANIFEST.MF"
      }.toSeq
    },
    pomPostProcess := { pom: Node =>
      new RuleTransformer(new RewriteRule {
        override def transform(node: Node): NodeSeq = node match {
          case elem: Elem if elem.label == "dependency" &&
            (elem \ "groupId").text == "dev.zio" && (elem \ "artifactId").text == artifactName => NodeSeq.Empty
          case other => other
        }
      }).transform(pom).head
    }
  )
}
