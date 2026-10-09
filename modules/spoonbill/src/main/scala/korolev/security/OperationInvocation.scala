package spoonbill.security

import java.security.MessageDigest
import spoonbill.security.Identifiers.*
import spoonbill.security.store.InvocationStatus

enum RequestDigestError {
  case InvalidLength
}

/** Hash of a versioned, canonical operation intent, computed by trusted host
  * code. Never hash passwords/OTP inputs here, or randomized operation results.
  * A digest binds an invocation; its presence does not establish authority.
  */
final class RequestDigest private (private val value: Array[Byte]) {
  def bytes: Array[Byte] = value.clone()
  override def toString: String = "RequestDigest(<redacted>)"
  override def equals(other: Any): Boolean = other match {
    case that: RequestDigest => MessageDigest.isEqual(value, that.value)
    case _ => false
  }
  override def hashCode(): Int = java.util.Arrays.hashCode(value)
}

object RequestDigest {
  def fromBytes(value: Array[Byte]): Either[RequestDigestError, RequestDigest] =
    if (value.length == 32) Right(new RequestDigest(value.clone()))
    else Left(RequestDigestError.InvalidLength)
}

final case class OperationInvocation(
  invocationId: InvocationId,
  grantId: OperationAuthorizationId,
  binding: OperationBinding,
  requestDigest: RequestDigest
) {
  override def toString: String = "OperationInvocation(<redacted>)"
}

/** Durable status only. No operation result or sensitive disclosure is stored. */
final case class DurableInvocationRecord(invocation: OperationInvocation, status: InvocationStatus) {
  override def toString: String = s"DurableInvocationRecord($status)"
}
