package spoonbill.server

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import spoonbill.Context
import spoonbill.data.BytesLike
import spoonbill.effect.Effect
import spoonbill.effect.io.ServerSocket.ServerSocketHandler
import spoonbill.effect.syntax._
import spoonbill.state.{StateDeserializer, StateSerializer}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

abstract class SpoonbillApp[F[_]: Effect, B: BytesLike, S: StateSerializer: StateDeserializer, M](
  address: SocketAddress = new InetSocketAddress("localhost", 8080),
  gracefulShutdown: Boolean = false
) {

  implicit lazy val executionContext: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  val context: Context[F, S, M] = Context[F, S, M]

  val config: F[SpoonbillServiceConfig[F, S, M]]

  val channelGroup: AsynchronousChannelGroup =
    AsynchronousChannelGroup.withThreadPool(executionContext)

  private def logServerStarted(config: SpoonbillServiceConfig[F, S, M]) = Effect[F].delay {
    config.reporter.info(s"Server stated at $address")
  }

  private def addShutdownHook(config: SpoonbillServiceConfig[F, S, M], handler: ServerSocketHandler[F]) =
    Effect[F].delay {
      import config.reporter.Implicit
      Runtime.getRuntime.addShutdownHook(
        new Thread {
          override def run(): Unit = {
            config.reporter.info("Shutdown signal received.")
            config.reporter.info("Stopping serving new requests.")
            config.reporter.info("Waiting clients disconnection.")
            handler
              .stopServingRequests()
              .after(handler.awaitShutdown())
              .runSyncForget
          }
        }
      )
    }

  def main(args: Array[String]): Unit = {
    val job =
      for {
        cfg     <- config
        handler <- standalone.buildServer[F, B](spoonbillService(cfg), address, channelGroup, gracefulShutdown)
        _       <- logServerStarted(cfg)
        _       <- addShutdownHook(cfg, handler)
        _       <- handler.awaitShutdown()
      } yield ()
    Effect[F].run(job) match {
      case Left(e)  => e.printStackTrace()
      case Right(_) =>
    }
  }
}
