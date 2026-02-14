package spoonbill

object SystemPropertyFixture {

  private val lock = new Object

  def withSystemProperty[A](key: String, value: String)(f: => A): A =
    lock.synchronized {
      val previous = Option(System.getProperty(key))
      System.setProperty(key, value)
      try f
      finally {
        previous match {
          case Some(existing) => System.setProperty(key, existing)
          case None           => System.clearProperty(key)
        }
      }
    }
}
