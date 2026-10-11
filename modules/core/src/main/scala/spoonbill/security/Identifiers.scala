package spoonbill.security

import java.util.UUID

/**
 * Representation decoding never establishes authority. These IDs contain no
 * credentials.
 */
object Identifiers {
  opaque type AuthSessionId = UUID
  object AuthSessionId {
    def fromUuid(value: UUID): AuthSessionId = value
    extension (value: AuthSessionId) { def toUuid: UUID = value }
  }
  opaque type BrowserSessionSlotId = UUID
  object BrowserSessionSlotId { def fromUuid(value: UUID): BrowserSessionSlotId = value }
  opaque type BrowserBindingId = UUID
  object BrowserBindingId { def fromUuid(value: UUID): BrowserBindingId = value }
  opaque type CompletionId = UUID
  object CompletionId { def fromUuid(value: UUID): CompletionId = value }
  opaque type ViewSessionId = UUID
  object ViewSessionId { def fromUuid(value: UUID): ViewSessionId = value }
  opaque type ConnectionId = UUID
  object ConnectionId { def fromUuid(value: UUID): ConnectionId = value }
  opaque type ViewOwnerId = UUID
  object ViewOwnerId { def fromUuid(value: UUID): ViewOwnerId = value }
  opaque type InvocationId = UUID
  object InvocationId {
    def fromUuid(value: UUID): InvocationId = value
    extension (value: InvocationId) { def toUuid: UUID = value }
  }
  opaque type OperationAuthorizationId = UUID
  object OperationAuthorizationId {
    def fromUuid(value: UUID): OperationAuthorizationId = value
    extension (value: OperationAuthorizationId) { def toUuid: UUID = value }
  }
  opaque type RealmId = UUID
  object RealmId {
    def fromUuid(value: UUID): RealmId = value
    extension (value: RealmId) { def toUuid: UUID = value }
  }
  opaque type SubjectId = UUID
  object SubjectId {
    def fromUuid(value: UUID): SubjectId = value
    extension (value: SubjectId) { def toUuid: UUID = value }
  }
  opaque type OperationPurpose = UUID
  object OperationPurpose {
    def fromUuid(value: UUID): OperationPurpose = value
    extension (value: OperationPurpose) { def toUuid: UUID = value }
  }
  opaque type ResourceScope = UUID
  object ResourceScope {
    def fromUuid(value: UUID): ResourceScope = value
    extension (value: ResourceScope) { def toUuid: UUID = value }
  }
  opaque type CookieNamespace = UUID
  object CookieNamespace { def fromUuid(value: UUID): CookieNamespace = value }
}

enum VersionError {
  case Negative, Exhausted
}

/**
 * Separate counters deliberately have no implicit conversions between
 * namespaces.
 */
object Versions {
  opaque type SlotGeneration = Long
  object SlotGeneration {
    val initial: SlotGeneration                                     = 0L
    def fromLong(value: Long): Either[VersionError, SlotGeneration] = nonNegative(value)
    extension (value: SlotGeneration) {
      def toLong: Long                               = value
      def next: Either[VersionError, SlotGeneration] = increment(value)
    }
  }
  opaque type SecurityGeneration = Long
  object SecurityGeneration {
    val initial: SecurityGeneration                                     = 0L
    def fromLong(value: Long): Either[VersionError, SecurityGeneration] = nonNegative(value)
    extension (value: SecurityGeneration) {
      def toLong: Long                                   = value
      def next: Either[VersionError, SecurityGeneration] = increment(value)
    }
  }
  opaque type ViewOwnershipEpoch = Long
  object ViewOwnershipEpoch {
    val initial: ViewOwnershipEpoch                                     = 0L
    def fromLong(value: Long): Either[VersionError, ViewOwnershipEpoch] = nonNegative(value)
    extension (value: ViewOwnershipEpoch) {
      def toLong: Long                                   = value
      def next: Either[VersionError, ViewOwnershipEpoch] = increment(value)
    }
  }
  opaque type ViewRevision = Long
  object ViewRevision {
    val initial: ViewRevision                                     = 0L
    def fromLong(value: Long): Either[VersionError, ViewRevision] = nonNegative(value)
    extension (value: ViewRevision) {
      def toLong: Long                             = value
      def next: Either[VersionError, ViewRevision] = increment(value)
    }
  }

  private def nonNegative(value: Long): Either[VersionError, Long] =
    if (value < 0) Left(VersionError.Negative) else Right(value)

  private def increment(value: Long): Either[VersionError, Long] =
    if (value == Long.MaxValue) Left(VersionError.Exhausted) else Right(value + 1)
}
