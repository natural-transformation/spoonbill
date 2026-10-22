package spoonbill.auth

import java.util.UUID
import spoonbill.security.Versions.ViewOwnershipEpoch

/** Aggregate counts only, with no browser identifiers or presentation payloads.
 * Ownership retirement and safe history reclamation free separate capacities.
 */
final case class BrowserPersistenceUsage(
  retainedBindings: Long,
  retainedViews: Long,
  activeViews: Long,
  retainedCeremonies: Long,
  deliveryMaterials: Long,
  maxActiveViewsPerBinding: Long,
  maxCeremoniesPerBinding: Long,
  limits: BrowserPersistenceLimits,
  anonymousBindings: Long = 0L
) {

  /**
   * Poll from an administrative monitoring task, not a public request. Active
   * capacity and reclaimed history are tracked separately. Per-binding maxima deliberately
   * reveal no binding identifiers.
   */
  def warnings(atPercent: Int = 80): Vector[BrowserCapacityWarning] = {
    require(atPercent > 0 && atPercent <= 100, "Warning percentage must be in 1..100")
    Vector(
      BrowserCapacityWarning("retainedBindings", retainedBindings, limits.retainedBindings),
      BrowserCapacityWarning("anonymousBindings", anonymousBindings, limits.anonymousBindings),
      BrowserCapacityWarning("retainedViews", retainedViews, limits.retainedViews),
      BrowserCapacityWarning("retainedCeremonies", retainedCeremonies, limits.retainedCeremonies),
      BrowserCapacityWarning("deliveryMaterials", deliveryMaterials, limits.deliveryMaterials),
      BrowserCapacityWarning("activeViewsPerBinding", maxActiveViewsPerBinding, limits.activeViewsPerBinding),
      BrowserCapacityWarning("ceremoniesPerBinding", maxCeremoniesPerBinding, limits.ceremoniesPerBinding)
    ).filter(value => value.used >= (value.limit * atPercent + 99L) / 100L)
  }
}

final case class BrowserCapacityWarning(resource: String, used: Long, limit: Long) {
  def exhausted: Boolean = used >= limit
}

/** An administrative lookup result, never an authorization credential.
 * A retirement must still authorize its caller and compare this exact fence.
 */
final case class BrowserOwnedView(
  scope: BrowserProviderScope,
  viewId: String,
  ownerId: UUID,
  epoch: ViewOwnershipEpoch
) {
  override def toString: String = "BrowserOwnedView(<redacted>)"
}

/** A bounded page; concurrent ownership changes can alter subsequent pages. */
final case class BrowserOwnedViewPage(views: Vector[BrowserOwnedView], nextAfter: Option[String]) {
  override def toString: String = s"BrowserOwnedViewPage(${views.size},<redacted>)"
}
