package spoonbill.sensitive

/** Host-owned, read-only disclosure policy. Freshly validate subject, scope and
  * generation (or a credential-proven ceremony) before returning its stable
  * audience key. Public page access alone is not permission to disclose.
  */
trait SensitiveAccess[F[_], S] {
  def authorize(purpose: Purpose, state: S): F[Audience]
}
