package tui4s.use

import kyo.*

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.{FileAttribute, PosixFilePermissions}
import java.nio.file.{
  AtomicMoveNotSupportedException,
  FileSystems,
  Files,
  Path as JPath,
  StandardCopyOption,
}

/** A [[Storage]] in files (JVM only): one file for each key in `dir`, named
  * after the key (URL-encoded, so a key never leaves `dir`). A write goes to a
  * new file in `dir` and moves it over the old one in one atomic step, so a
  * reader never sees half a value. On a POSIX file system, a new directory and
  * each file are for the owner only (`rwx------`, `rw-------`).
  *
  * {{{
  * val storage = FileStorage(FileStorage.appDir("todo"))
  * }}}
  */
// claude 2026-10-19: created (L2a); changed: an example (L2a review)
final class FileStorage(val dir: JPath) extends Storage:

    /** The text under `key`, or Absent when its file does not exist. */
    def get(key: String)(using Frame): Maybe[String] < Sync =
      Sync.defer {
        val f = FileStorage.file(dir, key)
        if Files.isRegularFile(f) then Present(Files.readString(f, UTF_8))
        else Absent
      }

    /** Writes `value` under `key`: a new file, then an atomic move. */
    def set(key: String, value: String)(using Frame): Unit < Sync =
      Sync.defer {
        val posix = FileStorage.posix
        Files.createDirectories(dir, FileStorage.attrs(posix, "rwx------")*)
        val tmp   =
          Files.createTempFile(
            dir,
            ".tmp-",
            ".part",
            FileStorage.attrs(posix, "rw-------")*,
          )
        Files.writeString(tmp, value, UTF_8)
        val to    = FileStorage.file(dir, key)
        try
            Files.move(
              tmp,
              to,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING,
            )
        catch
            case _: AtomicMoveNotSupportedException =>
              Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING)
        end try
        ()
      }
end FileStorage

/** The file of a key and the data directory of an app. */
// claude 2026-10-19: created (L2a); changed: Scaladoc (L2a review)
object FileStorage:
    /** The file of `key` in `dir`. */
    def file(dir: JPath, key: String): JPath =
      dir.resolve(URLEncoder.encode(key, UTF_8).replace("*", "%2A") + ".txt")

    /** The data directory of the app `app`: Kyo's local data directory of the
      * platform (`Path.basePaths.dataLocal` of kyo-system: `$XDG_DATA_HOME` or
      * `~/.local/share` on Linux, `~/Library/Application Support` on macOS,
      * `%LOCALAPPDATA%` on Windows), then `app`.
      */
    def appDir(app: String): JPath =
      kyo.Path.basePaths.dataLocal.toJava.resolve(app)

    private def posix: Boolean =
      FileSystems.getDefault.supportedFileAttributeViews.contains("posix")

    private def attrs(posix: Boolean, perms: String): Seq[FileAttribute[?]] =
      if posix then
          Seq(
            PosixFilePermissions.asFileAttribute(
              PosixFilePermissions.fromString(perms),
            ),
          )
      else Seq.empty
end FileStorage

/** The storage of the JVM: [[FileStorage]] in the data directory of the app. */
// claude 2026-10-19: created (L2a)
private[use] object StoragePlatform:
    def forApp(app: String)(using Frame): Storage < Sync =
      Sync.defer(FileStorage(FileStorage.appDir(app)))
