import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, WritableByteChannel}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import spoonbill.recovery.*

/**
 * A payload adapter over one directory. Each reference owns a payload file, a
 * tombstone file and a lock file. Every put and delete runs under the
 * reference's in-process lock and its advisory file lock, so retirement and
 * write admission serialize at the store: a put that observes the tombstone
 * answers Retired, and a delete that runs first leaves the tombstone the put
 * will see. Durable means the bytes and the directory entry were forced to
 * disk before acknowledgment; the move becomes visible before directory force.
 * A matching duplicate repeats directory force in case an earlier put failed
 * after its move. The adapter never reclaims tombstones or lock files.
 *
 * This demonstration assumes an already durably provisioned local directory,
 * reliable advisory locks, atomic moves and directory force on the selected
 * filesystem, with every writer using this protocol. Creating the directory
 * here is a convenience, not proof of its parent entry's crash durability.
 * The laws do not establish these filesystem guarantees or qualify network
 * filesystems; unsupported force operations fail closed.
 */
final class FilePayloadStore(root: Path)(using blocking: ExecutionContext) extends SnapshotPayloadStore[Future] {
  Files.createDirectories(root)
  private val locks                  = new ConcurrentHashMap[UUID, AnyRef]()
  def cleanupBasis: CleanupBasis     = CleanupBasis.DurableRetirementFence
  private def payload(ref: PayloadRef): Path   = root.resolve(ref.id.toString + ".payload")
  private def tombstone(ref: PayloadRef): Path = root.resolve(ref.id.toString + ".retired")
  private def lockFile(ref: PayloadRef): Path  = root.resolve(ref.id.toString + ".lock")

  private def locked[A](ref: PayloadRef)(body: => A): A =
    locks.computeIfAbsent(ref.id, _ => new Object).synchronized {
      val channel = FileChannel.open(lockFile(ref), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try {
        val lock = channel.lock()
        try body
        finally lock.release()
      } finally channel.close()
    }

  private def syncDirectory(): Unit = {
    val channel = FileChannel.open(root, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()
  }

  def put(ref: PayloadRef, bytes: SnapshotBytes): Future[PayloadWrite] = Future {
    try
      locked(ref) {
        if (Files.exists(tombstone(ref))) PayloadWrite.Retired
        else if (Files.exists(payload(ref))) {
          if (java.util.Arrays.equals(Files.readAllBytes(payload(ref)), bytes.toArray)) {
            syncDirectory()
            PayloadWrite.Durable
          }
          else PayloadWrite.Conflict
        } else {
          val temporary = Files.createTempFile(root, ref.id.toString, ".staging")
          try {
            val channel = FileChannel.open(temporary, StandardOpenOption.WRITE)
            try {
              PayloadFileIO.writeFully(channel, ByteBuffer.wrap(bytes.toArray))
              channel.force(true)
            } finally channel.close()
            Files.move(temporary, payload(ref), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory()
            PayloadWrite.Durable
          } catch {
            case NonFatal(_) =>
              try Files.deleteIfExists(temporary)
              catch { case NonFatal(_) => () }
              PayloadWrite.Uncertain
          }
        }
      }
    catch { case NonFatal(_) => PayloadWrite.Unavailable }
  }

  def get(ref: PayloadRef): Future[PayloadRead] = Future {
    try
      if (Files.exists(tombstone(ref)) || !Files.exists(payload(ref))) PayloadRead.Missing
      else if (Files.size(payload(ref)) > SnapshotBytes.MaximumLength) PayloadRead.Missing
      else PayloadRead.Found(SnapshotBytes.copyOf(Files.readAllBytes(payload(ref))))
    catch { case NonFatal(_) => PayloadRead.Unavailable }
  }

  def delete(ref: PayloadRef): Future[PayloadDelete] = Future {
    try
      locked(ref) {
        val channel = FileChannel.open(tombstone(ref), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try channel.force(true)
        finally channel.close()
        Files.deleteIfExists(payload(ref))
        syncDirectory()
        PayloadDelete.FinalDeleted
      }
    catch { case NonFatal(_) => PayloadDelete.Uncertain }
  }
}

/** Example-local I/O helper, separated so short writes can be tested deterministically. */
object PayloadFileIO {
  def writeFully(channel: WritableByteChannel, buffer: ByteBuffer): Unit =
    while (buffer.hasRemaining) {
      if (channel.write(buffer) <= 0)
        throw new IOException("Payload write made no progress")
    }
}
