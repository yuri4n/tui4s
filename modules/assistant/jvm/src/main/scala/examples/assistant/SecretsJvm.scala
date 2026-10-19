package examples.assistant

import kyo.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.{
  PosixFileAttributes,
  PosixFilePermission,
  PosixFilePermissions,
}
import java.nio.file.{Files, LinkOption, Path as JPath}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The access to the key file: the one file that [[SecretsJvm]] reads. A seam,
  * so that a test sees each path that the interpreter touches.
  */
// claude 2026-10-19: created (L3)
trait KeyFile:
    /** The permissions of the regular file `path`; Absent when there is no file
      * there. A link, a directory, or a file system with no POSIX modes is a
      * [[SecretsError.Unreadable]].
      */
    def permissions(path: JPath)(using
        Frame,
    ): Maybe[Set[PosixFilePermission]] < (Sync & Abort[SecretsError])

    /** The text of the file `path` (UTF-8). */
    def read(path: JPath)(using Frame): String < (Sync & Abort[SecretsError])
end KeyFile

/** The key file on the file system of the JVM. */
// claude 2026-10-19: created (L3)
object KeyFile:
    /** `java.nio.file` with no link followed. */
    val real: KeyFile = new KeyFile:
        def permissions(path: JPath)(using
            Frame,
        ): Maybe[Set[PosixFilePermission]] < (Sync & Abort[SecretsError]) =
          attempt(path) {
            if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then Right(Absent)
            else
                val a = Files.readAttributes(
                  path,
                  classOf[PosixFileAttributes],
                  LinkOption.NOFOLLOW_LINKS,
                )
                if !a.isRegularFile then
                    Left("it is not a regular file (a link or a directory)")
                else Right(Present(a.permissions.nn.asScala.toSet))
          }

        /** Opens `path` with no link followed (`O_NOFOLLOW`): a link that takes
          * the place of the file after the check of its permissions is an
          * error, not the text of its target.
          */
        // claude 2026-10-19: changed: no link followed in the read (L3 review)
        def read(path: JPath)(using
            Frame,
        ): String < (Sync & Abort[SecretsError]) =
          attempt(path)(
            Right(
              Using.resource(
                Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).nn,
              )(in => String(in.readAllBytes().nn, UTF_8)),
            ),
          )

        private def attempt[A](path: JPath)(f: => Either[String, A])(using
            Frame,
        ): A < (Sync & Abort[SecretsError]) =
          Abort
            .run[Throwable](Sync.defer(f))
            .map {
              case Result.Success(Right(a))  => a
              case Result.Success(Left(why)) =>
                Abort.fail(SecretsError.Unreadable(path.toString, why))
              case other                     =>
                Abort.fail(
                  SecretsError.Unreadable(path.toString, other.toString),
                )
            }
end KeyFile

/** The keys on the JVM ([[Secrets]]). For a provider, the interpreter reads its
  * environment variable (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`); when it is not
  * set or empty, the key file of the app: `<config>/tui4s/assistant/keys`,
  * where `<config>` is kyo-system's config directory of the platform
  * (`$XDG_CONFIG_HOME` or `~/.config` on Linux, `~/Library/Application Support`
  * on macOS). It reads the file only when it is a regular file for its owner
  * only (mode 0600 or 0400): else it fails with [[SecretsError.OpenToOthers]],
  * whose text gives the `chmod` that fixes it. A line of the file is `NAME=key`
  * (the name of the environment variable); an empty line and a line that starts
  * with `#` are skipped.
  *
  * It reads no other file: never the credentials of another tool (such as
  * `~/.claude/.credentials.json` or `~/.codex/auth.json`), and never a login of
  * a web account. It never logs or prints a key.
  */
// claude 2026-10-19: created (L3)
object SecretsJvm:
    /** The key file of the app. */
    def keyFile: JPath =
      Path.basePaths.config.toJava
        .resolve("tui4s")
        .nn
        .resolve("assistant")
        .nn
        .resolve("keys")
        .nn

    /** An environment variable of the JVM (kyo-system's `System.env`). */
    def environment(name: String)(using Frame): Maybe[String] < Sync =
      System.env[String](name)

    /** The interpreter: `env` gives the environment, `file` is the key file,
      * `files` reads it.
      */
    def make(
        env: String => Maybe[String] < Sync,
        file: JPath = keyFile,
        files: KeyFile = KeyFile.real,
    ): Secrets = new Secrets:
        def apiKey(provider: ProviderChoice)(using
            Frame,
        ): Maybe[ApiKey] < (Sync & Abort[SecretsError]) =
          Secrets.variable(provider) match
              case Absent     => Absent
              case Present(n) =>
                env(n).map(v =>
                  v.flatMap(ApiKey.parse(_, KeySource.Environment(n)))
                    .fold(fromFile(n))(k => Present(k)),
                )

        private def fromFile(name: String)(using
            Frame,
        ): Maybe[ApiKey] < (Sync & Abort[SecretsError]) =
          files.permissions(file).map {
            case Absent                        => Absent
            case Present(perms) if open(perms) =>
              Abort.fail(
                SecretsError.OpenToOthers(
                  file.toString,
                  PosixFilePermissions.toString(perms.asJava),
                ),
              )
            case Present(_)                    =>
              files.read(file).map(text => SecretsJvm.find(text, name, file))
          }
    end make

    /** True when someone other than the owner can read or write. */
    private def open(perms: Set[PosixFilePermission]): Boolean =
      perms.exists(p =>
        p.name.nn.startsWith("GROUP") || p.name.nn.startsWith("OTHERS"),
      )

    /** The key of the line `name=key` of the text of a key file. */
    def find(text: String, name: String, file: JPath): Maybe[ApiKey] =
      Maybe
        .fromOption(
          text.linesIterator
            .map(_.trim)
            .filterNot(l => l.isEmpty || l.startsWith("#"))
            .map(_.stripPrefix("export ").trim)
            .collectFirst {
              case l if l.startsWith(name + "=") => l.drop(name.length + 1)
            },
        )
        .flatMap(v =>
          ApiKey.parse(
            v.stripPrefix("\"").stripSuffix("\""),
            KeySource.File(file.toString),
          ),
        )
end SecretsJvm
