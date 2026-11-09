import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.{Base64, UUID}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.recovery.*

/** Local filesystem/process evidence only: these tests do not simulate power loss. */
class FilePayloadProcessSpec extends AnyFunSuite {
  private val bytes = SnapshotBytes.copyOf("process-qualified draft".getBytes(UTF_8))

  // sbt normally loads tests outside java.class.path. Use the actual resolved
  // locations, which also works when this independent consumer is forked.
  private val workerClasspath = Seq(
    FilePayloadProcessWorker.getClass,
    classOf[FilePayloadStore],
    classOf[SnapshotBytes],
    classOf[scala.Option[?]],
    classOf[scala.deriving.Mirror]
  ).map(c => Path.of(c.getProtectionDomain.getCodeSource.getLocation.toURI).toString)
    .distinct.mkString(File.pathSeparator)

  private final class Worker(val process: Process, output: Path, ready: Path) {
    def awaitGate(): Unit = {
      val deadline = 15.seconds.fromNow
      while (!Files.exists(ready) && process.isAlive && deadline.hasTimeLeft())
        Thread.sleep(10)
      assert(Files.exists(ready), s"Worker did not reach its gate: ${Files.readString(output)}")
      assert(process.isAlive, s"Worker exited at its gate: ${Files.readString(output)}")
    }

    def release(): Unit = {
      val input = process.getOutputStream
      input.write("continue\n".getBytes(UTF_8))
      input.flush()
    }

    def result(): String = {
      assert(process.waitFor(15, TimeUnit.SECONDS), s"Worker timed out: ${Files.readString(output)}")
      val result = Files.readString(output).trim
      assert(process.exitValue() == 0, s"Worker failed: $result")
      result
    }

    def kill(): Unit = {
      if (process.isAlive) process.destroyForcibly()
      assert(process.waitFor(5, TimeUnit.SECONDS), "Worker did not terminate")
    }
  }

  private final class Fixture(val directory: Path) {
    val store: Path = Files.createDirectory(directory.resolve("store"))
    private val children = ArrayBuffer.empty[Worker]

    def start(mode: String, ref: PayloadRef, value: SnapshotBytes = bytes): Worker = {
      val controls = Files.createTempDirectory(directory, "worker-")
      val output = controls.resolve("output")
      val ready = controls.resolve("ready")
      val process = new ProcessBuilder(
        Path.of(sys.props("java.home"), "bin", "java").toString,
        "-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp", workerClasspath,
        "FilePayloadProcessWorker", mode, store.toString, ref.id.toString,
        Base64.getEncoder.encodeToString(bytes.toArray),
        Base64.getEncoder.encodeToString(value.toArray), ready.toString
      ).redirectErrorStream(true).redirectOutput(output.toFile).start()
      val worker = new Worker(process, output, ready)
      children += worker
      worker
    }

    def assertRetired(ref: PayloadRef): Unit = {
      assert(start("put", ref).result() == "Retired")
      assert(start("get", ref).result() == "Missing")
      assert(!Files.exists(store.resolve(s"${ref.id}.payload")))
      assert(Files.exists(store.resolve(s"${ref.id}.retired")))
      val entries = Files.list(store)
      try assert(!entries.iterator().asScala.exists(_.toString.endsWith(".staging")))
      finally entries.close()
    }

    def close(): Unit = {
      // Stop every child even when a test or an earlier cleanup assertion fails.
      try children.foreach { worker =>
        if (worker.process.isAlive) worker.process.destroyForcibly()
      }
      finally children.foreach(_.kill())
    }
  }

  private def withFixture(test: Fixture => Unit): Unit = {
    val directory = Files.createTempDirectory("spoonbill-process-payloads-")
    val fixture = new Fixture(directory)
    try test(fixture)
    finally {
      fixture.close()
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally paths.close()
    }
  }

  test("independent JVMs enforce immutable writes and retirement admission") {
    withFixture { fixture =>
      val ref = PayloadRef.fresh(bytes)
      assert(fixture.start("put", ref).result() == "Durable")
      assert(fixture.start("put", ref).result() == "Durable")
      val conflict = SnapshotBytes.copyOf("different draft".getBytes(UTF_8))
      assert(fixture.start("put", ref, conflict).result() == "Conflict")
      assert(fixture.start("get", ref).result() == s"Found:${Base64.getEncoder.encodeToString(bytes.toArray)}")
      assert(fixture.start("delete", ref).result() == "FinalDeleted")
      fixture.assertRetired(ref)

      val neverWritten = PayloadRef.fresh(bytes)
      assert(fixture.start("delete", neverWritten).result() == "FinalDeleted")
      fixture.assertRetired(neverWritten)
    }
  }

  test("acknowledged retirement survives abrupt worker termination and a fresh JVM") {
    withFixture { fixture =>
      val ref = PayloadRef.fresh(bytes)
      assert(fixture.start("put", ref).result() == "Durable")
      val retiring = fixture.start("retire-and-wait", ref)
      // The marker is written only after the adapter returns FinalDeleted.
      retiring.awaitGate()
      retiring.kill()

      fixture.assertRetired(ref)
      assert(fixture.start("delete", ref).result() == "FinalDeleted")
    }
  }

  test("deletion overtakes an upload queued in another JVM before write admission") {
    withFixture { fixture =>
      val ref = PayloadRef.fresh(bytes)
      val uploading = fixture.start("paused-put", ref)
      uploading.awaitGate()
      // put has returned a pending Future, but its body cannot run until the
      // parent releases stdin. Deletion therefore finishes before admission;
      // no sleep duration or scheduler race determines this ordering.
      assert(fixture.start("delete", ref).result() == "FinalDeleted")
      uploading.release()
      assert(uploading.result() == "Retired")
      fixture.assertRetired(ref)
    }
  }
}

/** Test-only entry point; each invocation constructs a new adapter and JVM. */
object FilePayloadProcessWorker {
  def main(args: Array[String]): Unit = {
    val Array(mode, directory, id, encodedReference, encodedValue, ready) = args: @unchecked
    val referenceBytes = SnapshotBytes.copyOf(Base64.getDecoder.decode(encodedReference))
    val ref = PayloadRef(UUID.fromString(id), referenceBytes.digest, referenceBytes.length)
    val value = SnapshotBytes.copyOf(Base64.getDecoder.decode(encodedValue))
    given ExecutionContext = ExecutionContext.global
    val store = new FilePayloadStore(Path.of(directory))

    def gate(): Unit = {
      Files.writeString(Path.of(ready), "ready", UTF_8)
      require(scala.io.StdIn.readLine() == "continue", "Parent closed the process gate")
    }

    mode match {
      case "put"    => println(Await.result(store.put(ref, value), 10.seconds))
      case "delete" => println(Await.result(store.delete(ref), 10.seconds))
      case "get" =>
        Await.result(store.get(ref), 10.seconds) match {
          case PayloadRead.Found(found) => println(s"Found:${Base64.getEncoder.encodeToString(found.toArray)}")
          case other                    => println(other)
        }
      case "retire-and-wait" =>
        require(Await.result(store.delete(ref), 10.seconds) == PayloadDelete.FinalDeleted)
        gate()
      case "paused-put" =>
        val queued = new LinkedBlockingQueue[Runnable]()
        val paused = new ExecutionContext {
          def execute(runnable: Runnable): Unit = { queued.add(runnable); () }
          def reportFailure(cause: Throwable): Unit = throw cause
        }
        val pending = new FilePayloadStore(Path.of(directory))(using paused).put(ref, value)
        val runnable = queued.poll(10, TimeUnit.SECONDS)
        require(runnable != null, "Upload was not queued")
        require(!pending.isCompleted, "Upload escaped the process gate")
        gate()
        runnable.run()
        println(Await.result(pending, 10.seconds))
      case other => throw new IllegalArgumentException(s"Unknown worker mode: $other")
    }
  }
}
