package tui4s.use

import kyo.*

/** A [[Storage]] in the `localStorage` of the browser (Scala.js only), through
  * kyo-ui's `UIWindow`. Each key gets `prefix` before it. It needs a browser:
  * Node.js has no `window`.
  */
// claude 2026-10-19: created (L2a)
final class LocalStorage(prefix: String) extends Storage:
    /** The text under `prefix + key`, or Absent. */
    def get(key: String)(using Frame): Maybe[String] < Sync =
      UIWindow.storageGet(prefix + key)

    /** Writes `value` under `prefix + key`. */
    def set(key: String, value: String)(using Frame): Unit < Sync =
      UIWindow.storageSet(prefix + key, value)
end LocalStorage

/** The storage of Scala.js: [[LocalStorage]] with the prefix `app/`. */
// claude 2026-10-19: created (L2a)
private[use] object StoragePlatform:
    def forApp(app: String)(using Frame): Storage < Sync =
      Sync.defer(LocalStorage(s"$app/"))
