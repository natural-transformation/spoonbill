package spoonbill.action

/** A typed action's update or dedicated control outcome. */
sealed trait UiOutcome[S]

/**
 * Pure update descriptions. NoUpdate requests no transition; Modify always
 * requests one, even for identity. Callback purity remains an application
 * contract. Only the owning view interprets these descriptions.
 */
enum StateUpdate[S] extends UiOutcome[S]:
  case NoUpdate()
  case Modify(reduce: S => S)

  def andThen(next: StateUpdate[S]): StateUpdate[S] = this match
    case NoUpdate() => next
    case Modify(first) =>
      next match
        case NoUpdate()     => this
        case Modify(second) => Modify(state => second(first(state)))

  def zoom[Parent](read: Parent => S)(write: (Parent, S) => Parent): StateUpdate[Parent] = this match
    case NoUpdate()     => NoUpdate[Parent]()
    case Modify(reduce) => Modify(parent => write(parent, reduce(read(parent))))

object StateUpdate:
  def noUpdate[S]: StateUpdate[S] = NoUpdate[S]()

object UiOutcome:
  def modify[S](reduce: S => S): UiOutcome[S] = StateUpdate.Modify(reduce)
  def noUpdate[S]: UiOutcome[S]               = StateUpdate.noUpdate[S]

  final case class Invalid[S](error: InputError) extends UiOutcome[S]

  /**
   * Opaque delivery handle; never a session credential or authentication proof.
   */
  final case class CommitAuthentication[S](completionId: java.util.UUID) extends UiOutcome[S]

  /**
   * Restricted provider-issued completion, never ordinary application output.
   */
  final case class CommitReplacement[S](handoff: spoonbill.auth.CommittedReplacement) extends UiOutcome[S]

  /**
   * Non-bearer lookup for checked transport reconciliation. Ordinary output
   * authorization still applies; uncertainty never grants a handoff.
   */
  final case class RecoverReplacement[S](ceremonyId: spoonbill.auth.CeremonyId) extends UiOutcome[S]

  /** One-shot handoff; cached results retain no payload after transfer. */
  final case class PresentSensitive[S](
    region: spoonbill.sensitive.RegionId,
    purpose: spoonbill.sensitive.Purpose,
    disclosure: spoonbill.sensitive.SensitiveDisclosure,
    lifetime: scala.concurrent.duration.FiniteDuration,
    onResult: spoonbill.sensitive.DisclosureOutcome => StateUpdate[S]
  ) extends UiOutcome[S]
