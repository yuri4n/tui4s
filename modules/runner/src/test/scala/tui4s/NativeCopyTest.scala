package tui4s

import kyo.test.*
import tui4s.internal.NativeCrossterm

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** jatatui's `NativeLoader` writes the crossterm library into a new `jni-<n>`
  * directory of `java.io.tmpdir` on each start (about 900 KB), and nothing
  * deletes it. After the load, tui4s deletes the copy that this process loaded
  * (Linux: it finds it in `/proc/self/maps`; the mapping of a loaded library
  * stays when its file is deleted). The test loads the real library twice and
  * checks that its copy and its directory are gone. It is skipped where
  * `/proc/self/maps` is not readable and where the library does not load;
  * loading the library does not touch the terminal.
  */
// claude 2026-10-19: created (C3, negative control: fails when the copy stays)
class NativeCopyTest extends Test[Any]:
    private val maps = Path.of("/proc/self/maps")

    private def tmp: Path =
      Path
        .of(java.lang.System.getProperty("java.io.tmpdir", "/tmp"))
        .toRealPath()

    /** The `jni-*` directories of the temporary directory. */
    private def jniDirs(): Set[Path] =
        val s = Files.list(tmp)
        try
          s.iterator()
            .asScala
            .filter(p => p.getFileName.toString.startsWith("jni-"))
            .toSet
        finally s.close()
    end jniDirs

    /** The directories of the crossterm copies that this process maps, also of
      * the copies whose file is deleted.
      */
    private def mappedCopyDirs(): Set[Path] =
      Files
        .readAllLines(maps)
        .asScala
        .flatMap(_.trim.split("\\s+", 6).lift(5))
        .map(_.stripSuffix(" (deleted)"))
        .filter(_.matches(".*/jni-[^/]+/libnative-.+-crossterm\\.so"))
        .map(p => Path.of(p).getParent)
        .toSet

    private def loads(): Boolean =
      try
          NativeCrossterm().load()
          NativeCrossterm().load()
          true
      catch case NonFatal(_) => false

    "a load of the real crossterm leaves no jni-* copy in the temporary directory" in {
      if !Files.isReadable(maps) then
          assert(true, "skipped: no /proc/self/maps")
      else
          val before = jniDirs()
          if !loads() then assert(true, "skipped: the library does not load")
          else
              val after = jniDirs()
              val ours  = mappedCopyDirs()
              val left  = ours.filter(Files.exists(_))
              assert(left.isEmpty, s"copies left: $left")
              assert((after -- before).intersect(ours).isEmpty)
      end if
    }
end NativeCopyTest
