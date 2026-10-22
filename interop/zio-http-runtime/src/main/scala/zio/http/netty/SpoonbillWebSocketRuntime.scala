package zio.http.netty

/** Fail closed if an application accidentally adds the unpatched upstream jar. */
object SpoonbillWebSocketRuntime {
  def verify(): Unit = {
    val handler = classOf[WebSocketAppHandler]
    require(handler.getDeclaredMethods.exists(_.getName == "spoonbillBoundedIngressVersion"),
      "Spoonbill requires its bounded ZIO HTTP runtime; the upstream handler was loaded")
    val resources = handler.getClassLoader.getResources("zio/http/netty/WebSocketAppHandler.class")
    var count = 0
    while (resources.hasMoreElements) {
      resources.nextElement()
      count += 1
    }
    require(count == 1,
      "Duplicate ZIO HTTP runtimes detected; exclude dev.zio:zio-http and retain spoonbill-zio-http-runtime")
  }
}
