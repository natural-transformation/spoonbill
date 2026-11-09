/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spoonbill.internal

import java.io.{File, RandomAccessFile}
import java.nio.ByteBuffer
import java.nio.file.Files
import avocet.RenderException
import avocet.impl.DiffRenderContext
import scala.util.control.NonFatal

private[spoonbill] object DevMode {

  private val DevModeKey              = "spoonbill.dev"
  private val DevModeDirectoryKey     = "spoonbill.dev.directory"
  private val DevModeDefaultDirectory = "target/spoonbill/"

  final class InvalidSavedRenderContext private[DevMode] (
    val originalFile: File,
    val retainedFile: File,
    cause: Throwable,
    quarantineFailure: Option[Throwable] = None
  ) extends RuntimeException(
        s"Cannot restore development render context '${originalFile.getAbsolutePath}'. " +
          s"Invalid rendering data is retained at '${retainedFile.getAbsolutePath}'. " +
          "A full page reload is required to create a new development session; do not reconnect the old DOM. " +
          quarantineFailure.fold("")(_ => "The file could not be quarantined; keep it until the old page has been closed or fully reloaded. "),
        cause
      ) {
    quarantineFailure.foreach(addSuppressed)
  }

  class ForRenderContext(identifier: String) {

    lazy val file = new File(DevMode.renderStateDirectory, identifier)
    private lazy val invalidFile = new File(file.getPath + ".invalid")

    lazy val saved: Boolean =
      DevMode.isActive && file.exists

    def isActive = DevMode.isActive

    def restoreRenderContext[M](): DiffRenderContext[M] = {
      // Keep rejecting this session after a restart or lost reload frame. A
      // missing original file must not make the old browser DOM a fresh baseline.
      if (isActive && invalidFile.exists())
        throw new InvalidSavedRenderContext(file, invalidFile, null)
      val savedBuffer = loadRenderContext()
      try DiffRenderContext[M](savedBuffer = savedBuffer)
      catch {
        case error: RenderException if error.code == RenderException.Code.InvalidSavedBuffer =>
          val quarantineFailure = try {
            Files.move(file.toPath, invalidFile.toPath)
            None
          } catch { case NonFatal(failure) => Some(failure) }
          throw new InvalidSavedRenderContext(
            file,
            if (quarantineFailure.isEmpty) invalidFile else file,
            error,
            quarantineFailure
          )
      }
    }

    def loadRenderContext() =
      if (saved) {
        val nioFile = new RandomAccessFile(file, "r")
        val channel = nioFile.getChannel
        try {
          val buffer = ByteBuffer.allocate(channel.size.toInt)
          nioFile.readFully(buffer.array())
          Some(buffer)
        } finally {
          nioFile.close()
          channel.close()
        }
      } else {
        None
      }

    def saveRenderContext(renderContext: DiffRenderContext[_]): Unit = {
      val nioFile = new RandomAccessFile(file, "rw")
      val channel = nioFile.getChannel
      try {
        val buffer = renderContext.save()
        while (buffer.hasRemaining) channel.write(buffer)
        channel.truncate(channel.position())
        ()
      } finally {
        nioFile.close()
        channel.close()
      }
    }
  }

  // NOTE:
  // We intentionally implement this as a `def` instead of a `val` so tests can
  // enable/disable dev mode via `-Dspoonbill.dev=true` without depending on class-load order.
  //
  // (In production this behaves like a constant anyway, because env vars/system properties
  // are typically fixed for the lifetime of the process.)
  def isActive: Boolean =
    sys.env
      .get(DevModeKey)
      .orElse(sys.props.get(DevModeKey))
      .fold(false)(_ == "true")

  lazy val workDirectory = {
    val directoryPath = sys.env
      .get(DevModeDirectoryKey)
      .orElse(sys.props.get(DevModeDirectoryKey))
      .getOrElse(DevModeDefaultDirectory)

    val file = new File(directoryPath)
    if (!file.exists()) {
      file.mkdirs()
    } else if (!file.isDirectory) {
      throw new ExceptionInInitializerError(s"$directoryPath should be directory")
    }
    file
  }

  lazy val sessionsDirectory = {
    val file = new File(workDirectory, "sessions")
    file.mkdir()
    file
  }

  lazy val renderStateDirectory = {
    val file = new File(workDirectory, "render-contexts")
    file.mkdir()
    file
  }
}
