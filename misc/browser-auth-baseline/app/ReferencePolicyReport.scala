package spoonbill.browserauthbaseline

/** Report the same executable policies selected by both server entry points. */
object ReferencePolicyReport {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "ReferencePolicyReport takes no arguments")
    val profiles = Vector("short", "representative").map { name =>
      val policy = MemoryReferenceServer.workloadPolicy(Array(s"--proof=$name"))
      s"\"$name\":${policy.json}"
    }
    println(profiles.mkString("{", ",", "}"))
  }
}
