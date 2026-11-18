package spoonbill.akka.util

import _root_.akka.actor.ActorSystem
import _root_.akka.stream.Materializer
import _root_.akka.stream.scaladsl.{Sink, Source}
import org.reactivestreams.Publisher
import org.scalatest.BeforeAndAfterAll
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.akka.instances.*
import spoonbill.effect.{Effect, Stream}
import spoonbill.reactivestreams.PublisherLateSubscriptionContract

final class PublisherLateSubscriptionSpec extends PublisherLateSubscriptionContract with BeforeAndAfterAll {
  private given ActorSystem  = ActorSystem("akka-publisher-late-subscription")
  private given Materializer = Materializer(summon[ActorSystem])

  protected def fanout(input: Stream[Future, Int])(using Effect[Future], ExecutionContext): Publisher[Int] =
    input.asPublisher(fanout = true)

  protected def materialize(publisher: Publisher[Int]): Future[Seq[Int]] =
    Source.fromPublisher(publisher).runWith(Sink.seq)

  override def afterAll(): Unit =
    try await(summon[ActorSystem].terminate())
    finally super.afterAll()
}
