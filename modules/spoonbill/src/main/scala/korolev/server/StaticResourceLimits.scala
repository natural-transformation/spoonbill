package spoonbill.server

/** Per-service limits for the built-in classpath /static route. */
final case class StaticResourceLimits(
  maxCacheEntries: Int = 64,
  maxCacheBytes: Long = 16L * 1024 * 1024,
  maxResourceBytes: Int = 4 * 1024 * 1024,
  maxConcurrentReads: Int = 4,
  maxPathLength: Int = 2048
) {
  require(maxCacheEntries >= 0, "maxCacheEntries must be nonnegative")
  require(maxCacheBytes >= 0, "maxCacheBytes must be nonnegative")
  require(
    maxResourceBytes > 0 && maxResourceBytes < Int.MaxValue,
    "maxResourceBytes must be positive and below Int.MaxValue"
  )
  require(maxConcurrentReads > 0, "maxConcurrentReads must be positive")
  require(maxPathLength > 0, "maxPathLength must be positive")
}
