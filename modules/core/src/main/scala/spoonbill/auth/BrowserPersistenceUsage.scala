package spoonbill.auth

import java.util.UUID
import spoonbill.security.Versions.ViewOwnershipEpoch

/** Aggregate counts only, with no browser identifiers or presentation payloads.
 * Retiring ownership frees active capacity; retained security history remains.
 */
final case class BrowserPersistenceUsage(
  retainedBindings: Long,
  retainedViews: Long,
  activeViews: Long,
  retainedCeremonies: Long,
  deliveryMaterials: Long,
  maxActiveViewsPerBinding: Long,
  maxCeremoniesPerBinding: Long,
  limits: BrowserPersistenceLimits
)

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
