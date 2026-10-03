package tui4s.interop.zio.ui

import apps.zio.full.SettingsApp
import munit.FunSuite
import scala.concurrent.duration.Duration as SDuration
import zio.*

/** The full mirror (52 elements): a user program runs headless, and the surface names only aliased data types. */
// claude 2026-10-03: created a run check and a surface check of the full mirror
class FullMirrorSuite extends FunSuite:
    override val munitTimeout = SDuration(120, "s")

    // claude 2026-10-03: created the runner of one scoped ZIO program
    private def run[A](z: ZIO[Scope, Any, A]): A =
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(ZIO.scoped(z)).getOrThrowFiberFailure())

    // claude 2026-10-03: created the run check: bound input, textarea, checkbox, select, then submit by the button
    test("full mirror: a settings form runs headless (bound inputs, select, submit through the form)") {
        val (before, after) = run(
            for
                h      <- Headless.mount(SettingsApp.app)
                before <- h.text
                _      <- h.input("name", "Ann") *> h.input("bio", "Hi") *> h.check("dark", true)
                _      <- h.keyDown("theme", "ArrowDown") // kyo-ui: ArrowDown on a select picks the next option
                _      <- h.click("save")
                after  <- h.text
            yield (before, after)
        )
        println(s"[full zio] before:\n$before\n[full zio] after:\n$after")
        assert(!before.contains("saved:"), before)
        assert(after.contains("saved: Ann|Hi|50|true|Dark"), after)
    }

    // claude 2026-10-03: created the surface check of the full mirror (more data aliases than the subset)
    test("full mirror surface: only aliased kyo-ui data types") {
        val leaks = surface.SurfaceLeaks.leaks(List("tui4s.interop.zio.ui", "tui4s.interop.zio.ui.internal"))
        val aliases = Set("KeyboardEvent", "MouseEvent", "WheelEvent", "Keyboard", "Modifiers", "InputFilter", "Href",
            "Target", "ImgSrc", "FileAccept", "ImageExt").map("kyo.UI." + _) ++ Set("kyo.Style", "kyo.Length")
        val unexpected = leaks.filterNot(_.onlyNames(aliases))
        println(s"[full zio surface] ${leaks.size} entries name a Kyo type, ${unexpected.size} outside the aliases")
        assert(unexpected.isEmpty, unexpected.mkString("\n"))
    }
end FullMirrorSuite
