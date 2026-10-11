import com.google.javascript.jscomp.{
  AbstractCommandLineRunner,
  CompilationLevel,
  Compiler,
  CompilerOptions,
  SourceFile,
  SourceMap
}
import com.google.javascript.jscomp.CompilerOptions.LanguageMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util
import sbt.{File, IO, Logger}
import scala.collection.JavaConverters._

object JsUtils {

  /** The Scala wire declarations are authoritative. This deliberately handles
    * only their closed, literal case-object syntax, not arbitrary Scala code.
    */
  def clientProtocol(frontend: File): String = {
    val declarations = IO.read(frontend)
    def entries(kind: String): Seq[(String, Int, Option[Int])] = {
      val declaration = ("case object\\s+(\\w+)\\s+extends " + kind + "\\((\\d+)(?:,\\s*(\\d+))?\\)").r
      val values = declaration.findAllMatchIn(declarations).map { entry =>
        (entry.group(1), entry.group(2).toInt, Option(entry.group(3)).map(_.toInt))
      }.toVector
      require(values.nonEmpty && values.map(_._2).sorted == values.indices,
        s"$kind must contain contiguous, unique literal wire codes")
      values.sortBy(_._2)
    }
    def name(value: String): String = value match {
      case "EvalJsResponse"          => "EVALJS_RESPONSE"
      case "SensitiveAcknowledgment" => "SENSITIVE_ACK"
      case other => other.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT)
    }
    val kinds = Seq("Procedure", "CallbackType", "PropertyType", "ModifyDomProcedure")
    val tables = kinds.map { kind =>
      val members = entries(kind).map { case (member, code, _) => s"  ${name(member)}: $code" }
      s"/** @enum {number} */\nexport const $kind = {\n${members.mkString(",\n")}\n};\n"
    }
    val widths = entries("ModifyDomProcedure").map { case (_, _, width) =>
      require(width.exists(_ > 0), "Every DOM operation must declare its operand count")
      width.get
    }
    "// Generated from Frontend.scala by JsUtils.clientProtocol; do not edit.\n\n" +
      tables.mkString("\n") + s"\nexport function domOperandWidths() { return [${widths.mkString(", ")}]; }\n"
  }

  def writeClientProtocol(frontend: File, destination: File): File = {
    IO.write(destination, clientProtocol(frontend))
    destination
  }

  def assembleJs(source: File, frontend: File, target: File, log: Logger): Seq[File] = {

    val generatedProtocol = clientProtocol(frontend)
    require(IO.read(new File(source, "protocol.js")) == generatedProtocol,
      "Generated browser protocol is stale; run spoonbill / generateBrowserProtocol")

    log.info("Assembling ES6 sources using Google Closure Compiler")

    val sourceOutputFile    = new File(target, "spoonbill-client.min.js")
    val sourceMapOutputFile = new File(target, "spoonbill-client.min.js.map")

    val (sourceOutput, compilationResult) = {
      val compiler = new Compiler()
      val externs = AbstractCommandLineRunner
        .getBuiltinExterns(CompilerOptions.Environment.BROWSER)

      val inputs = {
        val xs = source.listFiles().map { file =>
          val path    = file.getAbsolutePath
          val charset = StandardCharsets.UTF_8
          SourceFile.fromFile(path, charset)
        }
        util.Arrays.asList[SourceFile](xs: _*)
      }
      val options = {
        val options = new CompilerOptions()
        options.setLanguageIn(LanguageMode.ECMASCRIPT_2021)
        options.setLanguageOut(LanguageMode.ECMASCRIPT5_STRICT)
        options.setSourceMapIncludeSourcesContent(true)
        options.setSourceMapLocationMappings(
          List(new SourceMap.PrefixLocationMapping(source.getAbsolutePath, "spoonbill-sources")).asJava
        )
        options.setSourceMapOutputPath(sourceMapOutputFile.getName)
        options.setEnvironment(CompilerOptions.Environment.BROWSER)

        CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(options)
        options
      }
      val result = compiler.compile(externs, inputs, options)
      require(result.success, "Browser client compilation failed; refusing to publish a partial bundle")
      compiler.getSourceMap.setWrapperPrefix("(function(){")
      (compiler.toSource, result)
    }

    val sourceMapOutput = {
      val stringBuilder = new java.lang.StringBuilder()
      compilationResult.sourceMap.appendTo(stringBuilder, sourceMapOutputFile.getName)
      stringBuilder.toString
    }

    IO.write(
      sourceOutputFile,
      s"(function(){$sourceOutput}).call(this);\n//# sourceMappingURL=/static/spoonbill-client.min.js.map\n"
    )
    IO.write(sourceMapOutputFile, sourceMapOutput)
//
//    val spoonbillSources = new File(target, "spoonbill-sources")
//    val mappingSourceFiles = source
//      .listFiles()
//      .filter(_.isFile)
//      .map { file =>
//        val targetFile = new File(spoonbillSources, file.getName)
//        IO.copyFile(file, targetFile)
//        targetFile
//      }
//
//    mappingSourceFiles ++
    Seq(sourceOutputFile, sourceMapOutputFile)
  }
}
