import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.file.{Files, StandardCopyOption}

abstract class SourceGenerator(outputFile: File) {

  private val generatedBytes = new ByteArrayOutputStream
  private val printStream = new PrintStream(generatedBytes, false, "UTF-8")

  private val indentStep = "  "

  private var currentIndent = ""

  /** Override this to implement the generator. */
  protected def apply(): Unit

  /** Publish changed output atomically, so compilers never read partial sources or recompile unchanged generated files. */
  final def run: List[File] = {
    try apply() finally printStream.close()
    val contents = generatedBytes.toByteArray
    if (!outputFile.isFile || !java.util.Arrays.equals(Files.readAllBytes(outputFile.toPath), contents)) {
      outputFile.getParentFile.mkdirs()
      val temporary = Files.createTempFile(outputFile.getParentFile.toPath, outputFile.getName, ".tmp")
      try {
        Files.write(temporary, contents)
        Files.move(temporary, outputFile.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } finally Files.deleteIfExists(temporary)
    }
    List(outputFile)
  }

  protected def enter(prefix: String, suffix: String = "")(inside: => Unit): Unit = {
    line(prefix)
    val originalIndent = currentIndent
    currentIndent = currentIndent + indentStep
    inside
    currentIndent = originalIndent
    if (suffix.nonEmpty) {
      line(suffix)
    }
  }

  protected def line(str: String): Unit = {
    printStream.print(currentIndent)
    printStream.println(str)
  }

  protected def line(): Unit = {
    printStream.println()
  }

  /** Emit declarations (e.g. parameter lists) one per line, without a trailing comma. */
  protected def paramsOnePerLine(decls: Seq[String]): Unit = {
    decls.zipWithIndex.foreach { case (decl, i) =>
      line(if (i < decls.size - 1) s"$decl," else decl)
    }
  }

  protected def tupleType(size: Int, prefix: String = "T", suffix: String = "", separator: String = ", "): String =
    tupleTypeRaw(size, prefix, suffix).mkString(separator)

  protected def tupleAccess(size: Int, varName: String): String = {
    tupleAccessRaw(size, varName).mkString(", ")
  }

  private def tupleTypeRaw(size: Int, prefix: String = "T", suffix: String = ""): Seq[String] = {
    (1 to size).map(i => s"${prefix}${i}${suffix}")
  }

  private def tupleAccessRaw(size: Int, varName: String): Seq[String] = {
    (1 to size).map(i => s"${varName}._${i}")
  }

}
