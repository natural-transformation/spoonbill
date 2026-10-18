package spoonbill

import avocet.Document
import avocet.dsl.html.form
import avocet.events.EventPhase
import avocet.impl.{Html5RenderContext, TextPrettyPrintingConfig}
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeoutException, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.action.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.internal.{Frontend, SensitiveDisclosureScope}
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.sensitive.*
import spoonbill.util.JsCode
import spoonbill.web.FormData

/** Executes the event installed by the real typed form binding. */
private[spoonbill] final class ActionFormsRuntimeFixture[S](
  currentState: S,
  fields: Vector[(String, String)] = Vector.empty,
  current: () => Boolean = () => true
)(using Effect[Future], ExecutionContext)
    extends Context.BaseAccessDefault[Future, S, Unit]:
  type Binding = Context.Binding[Future, S, Unit]
  private val timer       = Executors.newSingleThreadScheduledExecutor()
  private val expirations = new ConcurrentLinkedQueue[() => Unit]()
  private val scope = new SensitiveDisclosureScope(
    new Frontend.RpcDeadlineScheduler:
      def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit =
        expirations.add(expire)
        () => { expirations.remove(expire); () }
  )
  val presentationEntered         = Promise[Unit]()
  val acknowledgement             = Promise[DisclosureOutcome]()
  val retirements                 = new AtomicInteger()
  val stateReads                  = new AtomicInteger()
  private val recorded            = new ConcurrentLinkedQueue[S => S]()
  def transitions: Vector[S => S] = recorded.iterator().asScala.toVector
  def pendingHolders: Int         = scope.pendingCount
  def expirePending(): Unit       = expirations.iterator().asScala.toVector.foreach(_.apply())

  private def unexpected[A](operation: String): A = throw new AssertionError(s"Unexpected access operation: $operation")
  private def freshBinding(): InvocationBinding = new InvocationBinding(
    InvocationId.fromUuid(UUID.randomUUID()),
    ConnectionId.fromUuid(UUID.randomUUID()),
    Some(scope),
    new InvocationAdmission(current)
  )
  override private[spoonbill] def submittedFields: Future[Vector[(String, String)]]     = Future.successful(fields)
  override private[spoonbill] def actionBinding: Future[InvocationBinding]              = Future.successful(freshBinding())
  override private[spoonbill] def authenticatedActionBinding: Future[InvocationBinding] = actionBinding
  override private[spoonbill] def retireReplacement(binding: InvocationBinding): Future[Unit] =
    retirements.incrementAndGet()
    Future.unit
  override def presentSensitive(
    region: RegionId,
    purpose: Purpose,
    payload: SensitivePayload,
    lifetime: FiniteDuration
  ): Future[DisclosureOutcome] =
    presentationEntered.trySuccess(())
    acknowledgement.future

  def execute(attribute: Document.Attr[Binding]): Future[Unit] =
    var events = Vector.empty[Context.Event[Future, S, Unit]]
    val render = new Html5RenderContext[Binding](TextPrettyPrintingConfig.noPrettyPrinting):
      override def addMisc(binding: Binding): Unit = binding match
        case event: Context.Event[Future, S, Unit] => events :+= event
        case _                                 => unexpected("non-event form binding")
    form(attribute)(render)
    assert(events.size == 1, "Expected one actual typed form event")
    val event = events.head
    assert(event.`type` == "submit" && event.phase == EventPhase.Bubbling)
    event.effect(this)

  def bounded[A](running: Future[A], duration: FiniteDuration = 10.seconds): Future[A] =
    val result = Promise[A]()
    val deadline = timer.schedule(
      new Runnable:
        def run(): Unit = { result.tryFailure(new TimeoutException("Typed form fixture timed out")); () }
      ,
      duration.toMillis,
      TimeUnit.MILLISECONDS
    )
    running.onComplete { value =>
      result.tryComplete(value); deadline.cancel(false); ()
    }
    result.future

  def close(): Unit =
    acknowledgement.trySuccess(DisclosureOutcome.NotSent)
    scope.close()
    timer.shutdownNow()
    assert(scope.pendingCount == 0, "Sensitive holders leaked")
    assert(expirations.isEmpty, "Sensitive deadlines leaked")

  def state: Future[S] =
    stateReads.incrementAndGet()
    Future.successful(currentState)
  def transition(reduce: Transition[S]): Future[Unit]                                  = { recorded.add(reduce); Future.unit }
  def property(id: Context.ElementId): Context.PropertyHandler[Future]                 = unexpected("property")
  def focus(id: Context.ElementId): Future[Unit]                                       = unexpected("focus")
  override def clearSensitive(region: RegionId): Future[Unit]                          = unexpected("clearSensitive")
  def publish(message: Unit): Future[Unit]                                             = unexpected("publish")
  def downloadFormData(id: Context.ElementId): Future[FormData]                        = unexpected("downloadFormData")
  def downloadFiles(id: Context.ElementId): Future[List[(Context.FileHandler, Bytes)]] = unexpected("downloadFiles")
  def downloadFilesAsStream(id: Context.ElementId): Future[List[(Context.FileHandler, Stream[Future, Bytes])]] =
    unexpected("downloadFilesAsStream")
  def downloadFileAsStream(handler: Context.FileHandler): Future[Stream[Future, Bytes]] = unexpected(
    "downloadFileAsStream"
  )
  def listFiles(id: Context.ElementId): Future[List[Context.FileHandler]] = unexpected("listFiles")
  def uploadFile(name: String, stream: Stream[Future, Bytes], size: Option[Long], mimeType: String): Future[Unit] =
    unexpected("uploadFile")
  def resetForm(id: Context.ElementId): Future[Unit]                         = unexpected("resetForm")
  def transitionAsync(reduce: TransitionAsync[Future, S]): Future[Unit]      = unexpected("transitionAsync")
  def transitionForce(reduce: Transition[S]): Future[Unit]                   = unexpected("transitionForce")
  def transitionForceAsync(reduce: TransitionAsync[Future, S]): Future[Unit] = unexpected("transitionForceAsync")
  def afterRender(callback: Context.BaseAccess[Future, S, Unit] => Future[Unit]): Future[Unit] = unexpected(
    "afterRender"
  )
  def sessionId: Future[Qsid]                                                        = unexpected("sessionId")
  def evalJs(code: JsCode): Future[String]                                           = unexpected("evalJs")
  def registerCallback(name: String)(callback: String => Future[Unit]): Future[Unit] = unexpected("registerCallback")
  def eventData: Future[String]                                                      = unexpected("eventData")
  override private[spoonbill] def completeAuthentication(id: UUID): Future[Unit]     = unexpected("completeAuthentication")
  override private[spoonbill] def completeReplacement(
    handoff: spoonbill.auth.CommittedReplacement,
    binding: InvocationBinding
  ): Future[Unit] = unexpected("completeReplacement")
  override private[spoonbill] def recoverReplacement(id: spoonbill.auth.CeremonyId): Future[Unit] = unexpected(
    "recoverReplacement"
  )
