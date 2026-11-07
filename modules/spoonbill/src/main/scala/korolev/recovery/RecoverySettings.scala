package spoonbill.recovery

/**
 * Provider-side limits of the publication capability. A reservation that is
 * not published within reservationSeconds is retired and cleaned up; a read
 * pin older than readPinSeconds cannot adopt its bytes; a garbage claim lease
 * expires after claimLeaseSeconds and an uncertain delete waits
 * retryBackoffSeconds before it may be claimed again. maxOpenObligations is
 * the backpressure bound: once open cleanup obligations plus outstanding
 * reservations reach it, the provider refuses new reservations instead of
 * admitting unbounded retention, so repeated failed uploads cannot pile up
 * reservations and payloads within the reservation window.
 */
final case class SnapshotReferenceSettings(
  reservationSeconds: Long = 120,
  readPinSeconds: Long = 60,
  claimLeaseSeconds: Long = 120,
  retryBackoffSeconds: Long = 30,
  maxOpenObligations: Long = 65536
) {
  require(
    Vector(reservationSeconds, readPinSeconds, claimLeaseSeconds, retryBackoffSeconds)
      .forall(value => value > 0 && value <= 86400L),
    "Reference windows must be between one second and one day"
  )
  require(maxOpenObligations > 0, "maxOpenObligations must be positive")
}

/**
 * Binding-side limits. One maintenance sweep claims at most maintenanceBatch
 * retired references and deletes them one at a time; overlapping sweeps on one
 * binding coalesce instead of multiplying that bound.
 */
final case class PayloadRecoverySettings(maintenanceBatch: Int = 32) {
  require(maintenanceBatch > 0 && maintenanceBatch <= 1024, "maintenanceBatch must be between 1 and 1024")
}
