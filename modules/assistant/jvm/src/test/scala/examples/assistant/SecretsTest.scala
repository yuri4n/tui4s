package examples.assistant

import kyo.*
import kyo.test.*
import tui4s.use.FileStorage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import java.nio.file.{Files, Path as JPath}
import scala.jdk.CollectionConverters.*

/** The keys of the JVM ([[SecretsJvm]]) in a temporary directory, with a
  * recording seam: the environment wins, a 0600 file is read, a 0644 file is an
  * error that names the fix, a link is not followed, and the interpreter
  * touches no other path and no other variable. Also the store of the
  * conversations in files.
  */
// claude 2026-10-19: created (L3)
class SecretsTest extends Test[Any]:

    private def tempDir(using Frame): JPath < Sync =
      Sync.defer(Files.createTempDirectory("tui4s-assistant-").nn)

    private def write(f: JPath, text: String, mode: String)(using Frame) =
      Sync.defer {
        Files.writeString(f, text, UTF_8)
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(mode))
        ()
      }

    /** The real key file, and the paths that it touched. */
    private def recording(using
        Frame,
    ): (KeyFile, AtomicRef[Set[JPath]]) < Sync =
      AtomicRef
        .init(Set.empty[JPath])
        .map(seen =>
            val files = new KeyFile:
                def permissions(path: JPath)(using
                    Frame,
                ): Maybe[Set[PosixFilePermission]] <
                  (Sync & Abort[SecretsError])           =
                  seen
                    .updateAndGet(_ + path)
                    .andThen(KeyFile.real.permissions(path))
                def read(path: JPath)(using
                    Frame,
                ): String < (Sync & Abort[SecretsError]) =
                  seen.updateAndGet(_ + path).andThen(KeyFile.real.read(path))
            (files, seen),
        )

    /** An environment of `vars`, and the names that were read. */
    private def env(vars: Map[String, String])(using Frame) =
      AtomicRef
        .init(Set.empty[String])
        .map(asked =>
          (
            (n: String) =>
              asked.updateAndGet(_ + n).andThen(Maybe.fromOption(vars.get(n))),
            asked,
          ),
        )

    private val text =
      "# the keys\nANTHROPIC_API_KEY=sk-ant-FILE\n\nexport OPENAI_API_KEY=\"sk-oai-FILE\"\n"

    "the environment wins: the file is not touched" in {
      for
          dir           <- tempDir
          file           = dir.resolve("keys").nn
          _             <- write(file, text, "rw-------")
          (files, seen) <- recording
          (vars, asked) <- env(Map("ANTHROPIC_API_KEY" -> " sk-ant-ENV "))
          key           <- Abort.run[SecretsError](
                             SecretsJvm
                               .make(vars, file, files)
                               .apiKey(ProviderChoice.Anthropic),
                           )
          touched       <- seen.get
          names         <- asked.get
      yield assert(
        key.map(_.map(k => (k.reveal, k.source))) ==
          Result.succeed(
            Present(("sk-ant-ENV", KeySource.Environment("ANTHROPIC_API_KEY"))),
          ) &&
          touched.isEmpty && names == Set("ANTHROPIC_API_KEY"),
      )
    }

    "a 0600 file is read when the variable is not set; only the key file and the two variables are touched" in {
      for
          dir           <- tempDir
          file           = dir.resolve("keys").nn
          _             <- write(file, text, "rw-------")
          (files, seen) <- recording
          (vars, asked) <- env(Map("OPENAI_API_KEY" -> ""))
          secrets        = SecretsJvm.make(vars, file, files)
          a             <- Abort.run[SecretsError](secrets.apiKey(ProviderChoice.Anthropic))
          o             <- Abort.run[SecretsError](secrets.apiKey(ProviderChoice.OpenAI))
          f             <- Abort.run[SecretsError](secrets.apiKey(ProviderChoice.Fake))
          touched       <- seen.get
          names         <- asked.get
      yield assert(
        a.map(_.map(_.reveal)) == Result.succeed(Present("sk-ant-FILE")) &&
          o.map(_.map(_.reveal)) == Result.succeed(Present("sk-oai-FILE")) &&
          a.map(_.map(_.source)) == Result.succeed(
            Present(KeySource.File(file.toString)),
          ) &&
          f == Result.succeed(Absent) && touched == Set(file) &&
          names == Set("ANTHROPIC_API_KEY", "OPENAI_API_KEY"),
      )
    }

    "a 0644 file is an error that names the fix; the key does not show in it" in {
      for
          dir        <- tempDir
          file        = dir.resolve("keys").nn
          _          <- write(file, text, "rw-r--r--")
          (files, _) <- recording
          (vars, _)  <- env(Map.empty)
          r          <- Abort.run[SecretsError](
                          SecretsJvm
                            .make(vars, file, files)
                            .apiKey(ProviderChoice.Anthropic),
                        )
      yield r match
          case Result.Failure(e @ SecretsError.OpenToOthers(p, "rw-r--r--")) =>
            assert(
              p == file.toString && e.describe.contains(s"chmod 600 $file") &&
                !e.describe.contains("FILE"),
            )
          case other                                                         => fail(other.toString)
    }

    "a link is not followed; no file and no variable is no key" in {
      for
          dir       <- tempDir
          other      = dir.resolve("other.json").nn
          link       = dir.resolve("keys").nn
          _         <- write(other, text, "rw-------")
          _         <- Sync.defer(Files.createSymbolicLink(link, other))
          (vars, _) <- env(Map.empty)
          viaLink   <-
            Abort.run[SecretsError](
              SecretsJvm.make(vars, link).apiKey(ProviderChoice.Anthropic),
            )
          none      <- Abort.run[SecretsError](
                         SecretsJvm
                           .make(vars, dir.resolve("none").nn)
                           .apiKey(ProviderChoice.Anthropic),
                       )
      yield assert(
        (viaLink match
            case Result.Failure(SecretsError.Unreadable(_, _)) => true
            case _                                             => false
        ) &&
          none == Result.succeed(Absent),
      )
    }

    "the store of the conversations in files: a round trip, owner-only files" in {
      for
          dir   <- tempDir
          store  = ConversationStore.make(FileStorage(dir.resolve("app").nn))
          c      = Conversation(
                     ConversationId.parse("f1").get,
                     "a title",
                     Chunk(
                       Message(
                         Role.User,
                         "hi\nthere",
                         Instant.Epoch,
                         MessageStatus.Done,
                       ),
                     ),
                     Instant.Epoch,
                   )
          _     <- store.save(c)
          back  <- Abort.run[StoreError](store.load(c.id))
          ids   <- store.list
          modes <- Sync.defer {
                     val files = Files.list(dir.resolve("app")).nn
                     try
                       files.iterator.nn.asScala.toSeq.map(p =>
                         PosixFilePermissions.toString(
                           Files.getPosixFilePermissions(p),
                         ),
                       )
                     finally files.close()
                   }
      yield assert(
        back == Result.succeed(Present(c)) && ids == Chunk(c.id) &&
          modes.nonEmpty && modes.forall(_ == "rw-------"),
      )
    }
end SecretsTest
