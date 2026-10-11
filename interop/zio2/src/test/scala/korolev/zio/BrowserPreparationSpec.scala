package spoonbill.zio

import _root_.zio.{Promise, Task, ZIO}
import _root_.zio.test.{assertTrue, suite, test, ZIOSpecDefault}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, Future, Promise as ScalaPromise}
import scala.util.Try
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.transaction.*
import spoonbill.server.BrowserSessionToken

/**
 * A small native transaction adapter fixture: outer Future, inner ZIO Task. It
 * executes the production BrowserPreparation interpreter, stages host and
 * browser writes together, and has a real native acquisition/finalizer. The
 * test controls dispatch and commit acknowledgment without Await or unsafeRun.
 * It is bounded to one browser, view and ceremony per fixture.
 */
object BrowserPreparationSpec extends ZIOSpecDefault {
  private given Effect[Future]   = new Effect.FutureEffect
  private given ExecutionContext = ExecutionContext.parasitic
  private val time               = Instant.parse("2026-10-11T00:00:00Z")
  private val binding            = "b" * 43
  private val credential         = BrowserSessionToken.fromString("t" * 43).toOption.get
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => throw new IllegalStateException(error.toString), identity)
  private def reject(error: OperationError = OperationError.HostDenied): Nothing = throw new OperationProtocolException(
    error
  )
  private case class State(
    record: Option[BrowserCeremonyRecord] = None,
    hostWrites: Int = 0,
    prepared: Option[HostSessionPrepared] = None,
    generation: Long = 0,
    activated: Boolean = false
  )

  private final class Provider extends TransactionalBrowserPersistence[Future, Task, String] {
    val scope                                              = BrowserProviderScope("native-test", "session")
    val capabilities                                       = BrowserProviderCapabilities(true, false, BrowserOwnership.ExclusiveScope)
    private val issuer                                     = new BrowserCapabilityScope[String]
    val durable                                            = new AtomicReference(State())
    val constructions                                      = new AtomicInteger()
    val cleanups                                           = new AtomicInteger()
    val active                                             = new AtomicInteger()
    val loseAcknowledgement                                = new AtomicBoolean()
    val replayProgram                                      = new AtomicBoolean()
    val dispatched                                         = ScalaPromise[Task[Unit]]()
    private val released                                   = ScalaPromise[Unit]()
    private var owner                                      = Option.empty[BrowserView[String]]
    private var epoch                                      = 0L
    var beforeCommit: Task[Unit]                           = ZIO.unit
    def accepts(receipt: CommittedAuthentication): Boolean = issuer.owns(receipt)

    final class Transaction private[Provider] (val initial: State) {
      private val live                    = new AtomicBoolean()
      private val staged                  = new AtomicReference(initial)
      private[Provider] def open(): Unit  = live.set(true)
      private[Provider] def close(): Unit = live.set(false)
      private def check(): Unit           = if (!live.get()) reject(OperationError.ScopeClosed)
      def writeHost(): Unit = {
        check(); staged.updateAndGet(value => value.copy(hostWrites = value.hostWrites + 1)); ()
      }
      private[Provider] def browser(prepared: HostSessionPrepared): UUID = {
        check()
        val record    = staged.get().record.getOrElse(reject(OperationError.NotPrepared))
        val committed = accepted(record.committed)
        staged.set(staged.get().copy(record = Some(committed), prepared = Some(prepared)))
        committed.completionId
      }
      private[Provider] def value: State = { check(); staged.get() }
    }
    type Tx = Transaction

    private val executor = new TransactionExecutor[Future, Task, Transaction] {
      val program     = Zio2TransactionProgram
      val scopePolicy = TransactionScopePolicy.Serialized
      def transact[A](body: Transaction => Task[A]): Future[Either[TransactionFailure, A]] = {
        val result = ScalaPromise[Either[TransactionFailure, A]]()
        val tx     = new Transaction(durable.get())
        // A native runner may construct G before it starts evaluating G. The
        // shared interpreter must still defer the application's construction.
        val staged = body(tx)
        val work   = if (replayProgram.get()) staged.flatMap(_ => staged) else staged
        val native = ZIO
          .acquireReleaseWith(ZIO.succeed { tx.open(); active.incrementAndGet(); tx }) { value =>
            ZIO.succeed {
              value.close(); active.decrementAndGet(); cleanups.incrementAndGet(); released.trySuccess(()); ()
            }
          } { _ =>
            work
              .flatMap(value => beforeCommit.as(value))
              .map[Either[TransactionFailure, A]] { value =>
                if (!durable.compareAndSet(tx.initial, tx.value)) Left(TransactionFailure.RolledBack)
                else if (loseAcknowledgement.getAndSet(false)) Left(TransactionFailure.CommitUnknown)
                else Right(value)
              }
              .catchAll {
                case error: OperationProtocolException => ZIO.succeed(Left(TransactionFailure.Rejected(error.error)))
                case _                                 => ZIO.succeed(Left(TransactionFailure.RolledBack))
              }
          }
          .flatMap(outcome => ZIO.succeed { result.trySuccess(outcome); () })
          .onInterrupt(ZIO.succeed {
            result.tryFailure(new InterruptedException("Native browser transaction interrupted")); ()
          })
        dispatched.trySuccess(native)
        result.future
      }
    }
    private def checked(view: BrowserView[String]): Unit =
      if (!issuer.owns(view) || !owner.contains(view) || view.identity.generation != durable.get().generation) reject()
    private def outer[A](body: => A): Future[Either[BrowserFailure, A]] = Future.successful {
      synchronized {
        Try(body).toEither.left.map(_ => BrowserFailure.Denied)
      }
    }
    private val interpreter = new BrowserPreparation[Future, Task, Transaction, String](
      issuer,
      executor,
      ceremony => {
        outer {
          val view = ceremony.view.asInstanceOf[BrowserView[String]]
          checked(view)
          val record = durable.get().record.filter(_.id == ceremony.id).getOrElse(reject())
          accepted(record.validate(ceremony.browserKey, durable.get().generation, ceremony.purpose, time))
          durable.set(durable.get().copy(record = Some(accepted(record.admit))))
        }.map(_.left.map(_ => TransactionFailure.Rejected(OperationError.HostDenied)))
      }
    )
    def prepare(ceremony: BrowserCeremony)(
      host: Tx => Task[HostSessionPrepared]
    ): Future[Either[TransactionFailure, CommittedAuthentication]] =
      interpreter.prepare(ceremony) { transaction =>
        constructions.incrementAndGet()
        Zio2TransactionProgram.flatMap(host(transaction))(prepared => ZIO.attempt(transaction.browser(prepared)))
      }
    def capture(
      secret: String,
      token: Option[String],
      activate: Boolean
    ): Future[Either[BrowserFailure, BrowserIdentity[String]]] = outer {
      if (secret != binding || issuer.isClosed) reject()
      val current = durable.get()
      token.foreach(value => if (!current.prepared.exists(_.credential.value == value)) reject())
      if (activate && token.nonEmpty && !current.activated)
        durable.set(current.copy(generation = current.generation + 1, activated = true))
      val next = durable.get()
      if (token.isEmpty && next.activated) reject()
      val host = Option.when(next.activated)(HostPrincipal("subject", "subject", 1, 1, time.plusSeconds(600)))
      issuer.identity(secret, token, next.generation, host, Option.when(next.activated)(next.prepared.get.sessionId))
    }
    def claim(
      identity: BrowserIdentity[String],
      qsid: Qsid,
      connection: ConnectionId,
      existingOnly: Boolean
    ): Future[Either[BrowserFailure, BrowserView[String]]] = outer {
      if (!issuer.owns(identity) || identity.generation != durable.get().generation || (existingOnly && owner.isEmpty))
        reject()
      if (owner.exists(_.qsid != qsid)) reject(OperationError.CapacityExceeded)
      epoch += 1
      val view = issuer.view(identity, qsid, connection, UUID.randomUUID(), epoch)
      owner = Some(view); view
    }
    def authorize(view: BrowserView[String]): Future[Either[BrowserFailure, BrowserIdentity[String]]] = outer {
      checked(view); view.identity
    }
    def release(view: BrowserView[String]): Future[Unit] = Future.successful(synchronized {
      if (owner.contains(view)) owner = None
    })
    def begin(view: BrowserView[String], purpose: String): Future[Either[BrowserFailure, BrowserCeremony]] = outer {
      checked(view)
      if (durable.get().record.nonEmpty) reject(OperationError.CapacityExceeded)
      val record = BrowserCeremonyRecord(
        CeremonyId(UUID.randomUUID()),
        UUID.randomUUID(),
        view.identity.authorityStamp.bindingKey,
        view.identity.generation,
        purpose,
        time.plusSeconds(120)
      )
      durable.set(durable.get().copy(record = Some(record)))
      issuer.ceremony(view, record.id, purpose, record.expiresAt, None)
    }
    def bindChallenge(
      ceremony: BrowserCeremony,
      challenge: BrowserChallenge
    ): Future[Either[BrowserFailure, BrowserCeremony]] = outer {
      if (!issuer.owns(ceremony)) reject()
      val view = ceremony.view.asInstanceOf[BrowserView[String]]
      checked(view)
      val record = accepted(durable.get().record.filter(_.id == ceremony.id).getOrElse(reject()).bind(challenge))
      durable.set(durable.get().copy(record = Some(record)))
      issuer.ceremony(view, record.id, record.purpose, record.expiresAt, record.challenge)
    }
    def resume(
      view: BrowserView[String],
      id: CeremonyId,
      purpose: String
    ): Future[Either[BrowserFailure, BrowserCeremony]] = outer {
      checked(view)
      val record = durable.get().record.filter(_.id == id).getOrElse(reject())
      accepted(record.validate(view.identity.authorityStamp.bindingKey, view.identity.generation, purpose, time))
      if (record.phase != BrowserCeremonyPhase.Begun && record.phase != BrowserCeremonyPhase.Challenged)
        reject(OperationError.PermitUsed)
      issuer.ceremony(view, id, purpose, record.expiresAt, record.challenge)
    }
    def pending(view: BrowserView[String]): Future[Either[BrowserFailure, Option[BrowserContinuation]]] = outer {
      checked(view)
      durable
        .get()
        .record
        .map(record =>
          BrowserContinuation(
            record.id,
            record.purpose,
            record.challenge,
            record.phase == BrowserCeremonyPhase.Admitted || record.phase == BrowserCeremonyPhase.Committed,
            record.expiresAt
          )
        )
    }
    def recover(view: BrowserView[String], id: CeremonyId): Future[Either[BrowserFailure, BrowserRecovery]] = outer {
      checked(view)
      if (!released.isCompleted && dispatched.isCompleted) reject()
      val record = durable.get().record.filter(_.id == id).getOrElse(reject())
      if (record.phase == BrowserCeremonyPhase.Committed)
        BrowserRecovery.Committed(issuer.committed(record.completionId))
      else { durable.set(durable.get().copy(record = Some(record.reconciled))); BrowserRecovery.NotCommitted }
    }
    def deliver(id: UUID, secret: String): Future[Option[BrowserSessionToken]] = Future.successful {
      synchronized {
        val current = durable.get()
        if (
          secret == binding && !current.activated && current.record
            .exists(record => record.completionId == id && record.phase == BrowserCeremonyPhase.Committed)
        )
          current.prepared.map(_.credential)
        else None
      }
    }
    def logout(secret: String, token: Option[String]): Future[Either[BrowserFailure, Unit]] = outer {
      if (secret != binding) reject()
      durable.set(durable.get().copy(generation = durable.get().generation + 1, activated = false, prepared = None)); ()
    }
    def maintenance(): Future[Unit] = Future.unit // Fixed trusted time cannot reach expiry in this fixture.
    def close(): Future[Unit] = {
      val settlement = if (dispatched.isCompleted) released.future else Future.unit
      settlement.map { _ =>
        issuer.close(); owner = None
      }
    }
  }

  private def ceremony(provider: Provider): Task[(BrowserView[String], BrowserCeremony)] = for {
    captured <- ZIO.fromFuture(_ => provider.capture(binding, None, activate = false)).map(accepted)
    view <-
      ZIO
        .fromFuture(_ =>
          provider
            .claim(captured, Qsid("device", "view"), ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false)
        )
        .map(accepted)
    begun <- ZIO.fromFuture(_ => provider.begin(view, "login")).map(accepted)
  } yield view -> begun
  private def prepared: HostSessionPrepared =
    new HostSessionPrepared(UUID.randomUUID(), UUID.randomUUID(), time.plusSeconds(600), credential)
  private def permitUsed(value: Either[TransactionFailure, ?]): Boolean =
    value == Left(TransactionFailure.Rejected(OperationError.PermitUsed))

  override def spec = suite("Browser preparation with distinct Future and native ZIO effects")(
    test("defers G construction and publishes host/browser state and receipt only after outer commit") {
      val provider = new Provider
      val calls    = new AtomicInteger()
      for {
        ready   <- Promise.make[Nothing, Unit]
        commit  <- Promise.make[Nothing, Unit]
        _        = provider.beforeCommit = ready.succeed(()) *> commit.await
        context <- ceremony(provider)
        requested = provider.prepare(context._2) { tx =>
                      calls.incrementAndGet()
                      ZIO.yieldNow *> ZIO.attempt { tx.writeHost(); prepared }
                    }
        native         <- ZIO.fromFuture(_ => provider.dispatched.future)
        deferred        = provider.constructions.get() == 0 && calls.get() == 0 && provider.active.get() == 0
        running        <- native.fork
        _              <- ready.await
        uncommitted     = provider.durable.get()
        receiptWithheld = !requested.isCompleted
        _              <- commit.succeed(())
        _              <- running.join
        result         <- ZIO.fromFuture(_ => requested)
        duplicate      <- ZIO.fromFuture(_ => provider.prepare(context._2)(_ => ZIO.succeed(prepared)))
        recovered      <- ZIO.fromFuture(_ => provider.recover(context._1, context._2.id))
      } yield assertTrue(
        deferred,
        receiptWithheld,
        uncommitted.hostWrites == 0,
        uncommitted.prepared.isEmpty,
        result.exists(provider.accepts),
        provider.durable.get().hostWrites == 1,
        provider.durable.get().prepared.nonEmpty,
        provider.cleanups.get() == 1,
        provider.active.get() == 0,
        calls.get() == 1,
        permitUsed(duplicate),
        recovered.exists {
          case BrowserRecovery.Committed(value) => result.exists(_.completionId == value.completionId); case _ => false
        }
      )
    },
    test("native interruption closes the transaction, publishes no staged state, and never restores proof admission") {
      val provider = new Provider
      val retained = new AtomicReference[provider.Tx]()
      for {
        entered <- Promise.make[Nothing, Unit]
        context <- ceremony(provider)
        requested = provider.prepare(context._2) { tx =>
                      ZIO.attempt { retained.set(tx); tx.writeHost() } *> entered.succeed(()) *> ZIO.never
                    }
        native    <- ZIO.fromFuture(_ => provider.dispatched.future)
        running   <- native.fork
        _         <- entered.await
        exit      <- running.interrupt
        observed  <- ZIO.fromFuture(_ => requested).either
        handle    <- ZIO.attempt(retained.get().writeHost()).either
        before     = provider.durable.get()
        duplicate <- ZIO.fromFuture(_ => provider.prepare(context._2)(_ => ZIO.succeed(prepared)))
        recovery  <- ZIO.fromFuture(_ => provider.recover(context._1, context._2.id))
      } yield assertTrue(
        exit.isInterrupted,
        observed.isLeft,
        before.hostWrites == 0,
        before.prepared.isEmpty,
        before.record.exists(_.phase == BrowserCeremonyPhase.Admitted),
        provider.cleanups.get() == 1,
        provider.active.get() == 0,
        handle.left.exists {
          case error: OperationProtocolException => error.error == OperationError.ScopeClosed; case _ => false
        },
        permitUsed(duplicate),
        recovery == Right(BrowserRecovery.NotCommitted)
      )
    },
    test("re-evaluating an already successful G cannot commit its first staged host/browser writes") {
      val provider = new Provider
      provider.replayProgram.set(true)
      val calls = new AtomicInteger()
      for {
        context <- ceremony(provider)
        requested = provider.prepare(context._2) { tx =>
                      ZIO.attempt { calls.incrementAndGet(); tx.writeHost(); prepared }
                    }
        native <- ZIO.fromFuture(_ => provider.dispatched.future)
        _      <- native
        result <- ZIO.fromFuture(_ => requested)
      } yield assertTrue(
        permitUsed(result),
        calls.get() == 1,
        provider.constructions.get() == 1,
        provider.durable.get().hostWrites == 0,
        provider.durable.get().prepared.isEmpty,
        provider.cleanups.get() == 1,
        provider.active.get() == 0
      )
    },
    test("unknown acknowledgment keeps committed state but withholds receipt until authorized recovery") {
      val provider = new Provider
      provider.loseAcknowledgement.set(true)
      for {
        context  <- ceremony(provider)
        requested = provider.prepare(context._2)(tx => ZIO.attempt { tx.writeHost(); prepared })
        native   <- ZIO.fromFuture(_ => provider.dispatched.future)
        _        <- native
        result   <- ZIO.fromFuture(_ => requested)
        recovery <- ZIO.fromFuture(_ => provider.recover(context._1, context._2.id))
      } yield assertTrue(
        result == Left(TransactionFailure.CommitUnknown),
        provider.durable.get().hostWrites == 1,
        provider.durable.get().prepared.nonEmpty,
        recovery.exists {
          case BrowserRecovery.Committed(value) => provider.accepts(value)
          case _                                => false
        },
        provider.cleanups.get() == 1
      )
    }
  )
}
