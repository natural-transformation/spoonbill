package spoonbill.auth

/** Finite retention windows; expiry never resets an identity or ownership fence. */
final case class BrowserRetentionPolicy(
  idleBindingSeconds: Long = 600,
  idleViewSeconds: Long = 3600,
  historySeconds: Long = 120,
  maxSessionSeconds: Long = 86400
) {
  require(Vector(idleBindingSeconds, idleViewSeconds, historySeconds, maxSessionSeconds)
    .forall(value => value > 0 && value <= 31536000L), "Retention windows must be between one second and one year")
}
