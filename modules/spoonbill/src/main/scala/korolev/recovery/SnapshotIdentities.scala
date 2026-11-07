package spoonbill.recovery

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import spoonbill.auth.BrowserView
import spoonbill.security.Versions.{SecurityGeneration, SlotGeneration}
import spoonbill.snapshot.{SnapshotIdentity, SnapshotKeys}

/**
 * The identity binding of a projection, derived from public view fields only.
 * The subject is digested before it becomes a key, so no identifiable subject
 * text and no credential reaches a provider's reference metadata. Digests are
 * pseudonymous, not anonymous. None for an anonymous view.
 */
object SnapshotIdentities {
  def ofView[P](view: BrowserView[P]): Option[SnapshotIdentity] =
    for {
      principal <- view.principal
      sessionId <- view.identity.sessionId
      subject   <- SnapshotKeys.Subject.parse(digest(principal.subject)).toOption
      scope     <- SnapshotKeys.Scope.parse(s"policy:${principal.policyVersion}").toOption
      security  <- SecurityGeneration.fromLong(principal.securityVersion).toOption
      slot      <- SlotGeneration.fromLong(view.identity.generation).toOption
    } yield SnapshotIdentity(subject, scope, sessionId, security, slot)

  private def digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)).iterator.map(b => f"${b & 0xff}%02x").mkString
}
