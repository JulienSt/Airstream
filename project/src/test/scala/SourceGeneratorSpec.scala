import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/** Exercise the build generator without compiling Airstream's runtime modules. */
object SourceGeneratorSpec {

  def main(args: Array[String]): Unit = {
    preservesCompleteOutputWhileGenerating()
    println("SourceGenerator: complete output remains visible during generation")
    keepsUnchangedOutputTimestamp()
    println("SourceGenerator: identical output keeps its existing timestamp")
  }

  private def keepsUnchangedOutputTimestamp(): Unit = {
    val directory = Files.createTempDirectory("airstream-generator-test-")
    val output = directory.resolve("Generated.scala")
    def generate(): Unit = {
      new SourceGenerator(output.toFile) {
        override protected def apply(): Unit = line("object Generated {}")
      }.run
    }
    try {
      generate()
      val previousTime = FileTime.fromMillis(123456789000L)
      Files.setLastModifiedTime(output, previousTime)
      generate()
      assert(Files.getLastModifiedTime(output) == previousTime,
        "Identical generated content changed its timestamp and invalidated compiler products")
    } finally {
      Files.deleteIfExists(output)
      Files.deleteIfExists(directory)
    }
  }

  private def preservesCompleteOutputWhileGenerating(): Unit = {
    val directory = Files.createTempDirectory("airstream-generator-test-")
    val output = directory.resolve("Generated.scala")
    val previous = "object Previous {}\n".getBytes(UTF_8)
    Files.write(output, previous)
    val started = new CountDownLatch(1)
    val finish = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable]()
    val generator = new SourceGenerator(output.toFile) {
      override protected def apply(): Unit = {
        line("object Generated {")
        started.countDown()
        assert(finish.await(10, TimeUnit.SECONDS), "Generator completion was not released")
        line("}")
      }
    }
    val worker = new Thread(new Runnable {
      override def run(): Unit = {
        try generator.run
        catch { case error: Throwable => failure.set(error) }
      }
    })
    worker.start()
    try {
      assert(started.await(10, TimeUnit.SECONDS), "Generator did not reach the controlled midpoint")
      assert(java.util.Arrays.equals(Files.readAllBytes(output), previous),
        "Readers observed an incomplete generated source instead of the previous complete file")
    } finally {
      finish.countDown()
      worker.join(10000)
      try {
        assert(!worker.isAlive, "Generator thread did not finish")
        assert(failure.get() == null, String.valueOf(failure.get()))
        assert(new String(Files.readAllBytes(output), UTF_8) == "object Generated {\n}\n")
      } finally {
        Files.deleteIfExists(output)
        Files.deleteIfExists(directory)
      }
    }
  }
}
