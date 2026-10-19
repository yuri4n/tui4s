package tui4s.use

import kyo.*
import kyo.test.*

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path as JPath}
import scala.jdk.CollectionConverters.*

/** The file storage of the JVM: a round trip, a key that cannot leave the
  * directory, owner-only permissions, and no file left from a write.
  */
// claude 2026-10-19: created (L2a)
class FileStorageTest extends Test[Any]:

    private def tempDir(using Frame): JPath < Sync =
      Sync.defer(Files.createTempDirectory("tui4s-use-").resolve("app"))

    private def names(dir: JPath)(using Frame): Set[String] < Sync =
      Sync.defer {
        val files = Files.list(dir)
        try files.iterator.asScala.map(_.getFileName.toString).toSet
        finally files.close()
      }

    "a value comes back; a missing key is Absent; a key with / or .. stays in the directory" in {
      for
          dir   <- tempDir
          s      = FileStorage(dir)
          none  <- s.get("theme")
          _     <- s.set("theme", "dark")
          _     <- s.set("theme", "light")
          _     <- s.set("../../escape", "x")
          _     <- s.set("a/b", "y")
          theme <- s.get("theme")
          esc   <- s.get("../../escape")
          files <- names(dir)
      yield
          assert(
            none == Absent && theme == Present("light") && esc == Present("x"),
          )
          assert(files == Set("theme.txt", "..%2F..%2Fescape.txt", "a%2Fb.txt"))
    }

    "the directory and the files are for the owner only" in {
      for
          dir   <- tempDir
          _     <- FileStorage(dir).set("k", "v")
          perms <- Sync.defer(
                     (
                       PosixFilePermissions.toString(
                         Files.getPosixFilePermissions(dir),
                       ),
                       PosixFilePermissions.toString(
                         Files.getPosixFilePermissions(dir.resolve("k.txt")),
                       ),
                     ),
                   )
      yield assert(perms == ("rwx------", "rw-------"))
    }

    "usePersisted on files: a second run reads what the first one wrote" in {
      for
          dir <- tempDir
          _   <-
            Scope.run(usePersisted("count", 0, FileStorage(dir)).map(_.set(3)))
          n   <- Scope.run(usePersisted("count", 0, FileStorage(dir)).map(_.get))
      yield assert(n == 3)
    }

    "the storage of the app is under kyo-system's local data directory" in {
      val dir = FileStorage.appDir("tui4s-test")
      assert(dir.getFileName.toString == "tui4s-test")
      assert(dir.getParent == kyo.Path.basePaths.dataLocal.toJava)
    }
end FileStorageTest
