# NativeLoader leaves one `/tmp/jni-*` directory on each start

Draft for <https://github.com/oyvindberg/jatatui/issues> (the loader's own
error message points to tui-scala; the class is in the `crossterm` jar of
jatatui). Written 2026-10-19 by the tui4s maintainers.

## Summary

`tui.crossterm.NativeLoader` copies the crossterm library out of the jar into
a new temporary directory on each start of the JVM (or of a GraalVM native
image), and nothing deletes it. Each run of an app leaves one directory of
about 900 KB in `java.io.tmpdir`.

## Version

- `com.olvind.jatatui:crossterm:0.31.0` (and `jatatui-crossterm:0.31.0`)
- JDK 25 (Linux x86_64); also seen with a GraalVM 25 native image

## What happens (from the bytecode of `NativeLoader`)

1. `CrosstermJni`'s static initializer calls `NativeLoader.load("crossterm")`.
2. `load` calls `System.loadLibrary("crossterm")`. The library is not on
   `java.library.path`, so this fails with `UnsatisfiedLinkError`.
3. `loadPackaged` reads the resource
   `/libnative-<platform>-crossterm.so` (`.dylib`, `.dll`), calls
   `Files.createTempDirectory("jni-")`, copies the resource into it, and calls
   `System.load` on the copy.
4. Nothing deletes the file or the directory: no `deleteOnExit`, no cleanup.

## Measured

- One new directory per start: 904 KB (918,000 bytes for
  `libnative-x86_64-linux-crossterm.so`), with the JVM and with a native image.
- On one development machine: 753 directories; 532 of them came from one
  afternoon of start-up measurements (about 470 MB).
- In one native-image session, the count went from 169 to 244.

## Reproduction

```sh
ls -d /tmp/jni-* 2>/dev/null | wc -l     # n
# start and quit any app that uses jatatui-crossterm, e.g. a jatatui demo
ls -d /tmp/jni-* 2>/dev/null | wc -l     # n + 1
```

## Proposed fix (one of)

1. **Stable cache path.** Extract once to a path keyed by the content, for
   example `${XDG_CACHE_HOME:-~/.cache}/jatatui/<version>-<sha256 prefix>/` or
   `${java.io.tmpdir}/jatatui-<user>-<sha256 prefix>/`. Create the directory
   with owner-only permissions, write to a temporary file in it and rename it
   atomically, and reuse the file when its hash matches. Fall back to the
   current behavior when the directory is not writable.
2. **Delete after the load (POSIX).** On Linux and macOS, a loaded library
   stays mapped after its file is deleted. Delete the copy and its directory
   right after `System.load` returns. On Windows a loaded DLL cannot be
   deleted; there `deleteOnExit` on the file and the directory is the
   smallest fix, or option 1.
3. **A system property** (for example `tui.crossterm.libpath`) that names a
   library file to `System.load`, checked before the extraction, so that
   packagers can ship the library outside the jar.

## Workaround in tui4s

tui4s (`tui4s.internal.NativeCopy`) does option 2 from the outside on Linux:
after the load it finds the copy that the process loaded in
`/proc/self/maps` and deletes it and its directory. On macOS and Windows the
copy still stays.
