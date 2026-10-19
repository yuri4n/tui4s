package tui4s.widgets

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.*

/** Reads a kyo-ui tree through its public AST, with no renderer: the current
  * value of each `Reactive` and `Foreach`, the elements, the texts, and the
  * handlers of an element. The tests of the widgets use it for the structure of
  * a UI; the rendering tests are in the gallery of the examples project.
  */
// claude 2026-10-19: created (L2b)
object UiTree:

    /** All the nodes of `ui`, in document order, with the current value of each
      * signal.
      */
    def nodes(ui: UI)(using Frame): Chunk[UI] < Sync =
      ui match
          case r: Reactive[?]   => r.signal.current.map(nodes)
          case f: Foreach[a, ?] =>
            f.signal.current.map(xs =>
              Kyo
                .foreach(xs.zipWithIndex)((x, i) => nodes(f.render(i, x)))
                .map(_.flatten),
            )
          case f: Fragment[?]   => kids(f.children)
          case e: Element       => kids(e.children).map(e +: _)
          case other            => Chunk(other)

    private def kids(cs: Chunk[UI])(using Frame): Chunk[UI] < Sync =
      Kyo.foreach(cs)(nodes).map(_.flatten)

    /** The elements of `ui`. */
    def elements(ui: UI)(using Frame): Chunk[Element] < Sync =
      nodes(ui).map(_.collect { case e: Element => e })

    /** The text of `ui`: its texts, joined with no separator. */
    def text(ui: UI)(using Frame): String < Sync =
      nodes(ui).map(_.collect { case Text(v) => v }.mkString)

    /** The text of each element of `ui` with the role `role`. */
    def textsOf(ui: UI, role: String)(using Frame): Chunk[String] < Sync =
      elements(ui)
        .map(_.filter(_.attrs.role.contains(role)))
        .map(es => Kyo.foreach(es)(text))

    /** The first element of `ui` with the id `id`. */
    def byId(ui: UI, id: String)(using Frame): Maybe[Element] < Sync =
      elements(ui).map(es =>
        Maybe.fromOption(es.find(_.attrs.identifier.contains(id))),
      )

    /** The first element of `ui` with the role `role`. */
    def byRole(ui: UI, role: String)(using Frame): Maybe[Element] < Sync =
      elements(ui).map(es =>
        Maybe.fromOption(es.find(_.attrs.role.contains(role))),
      )

    /** The buttons of `ui`. */
    def buttons(ui: UI)(using Frame): Chunk[Button] < Sync =
      nodes(ui).map(_.collect { case b: Button => b })

    /** Runs the `onKeyDown` of the first element with the role `role`. */
    def press(
        ui: UI,
        role: String,
        key: Keyboard,
        mods: Modifiers = Modifiers.none,
    )(using
        Frame,
    ): Unit < Async =
      byRole(ui, role).map(e =>
        e.flatMap(_.attrs.onKeyDown)
          .fold(Kyo.unit)(h => h(KeyboardEvent(key, mods, Absent)).unit),
      )

    /** Runs the `onClick` of the button at `index`. */
    def click(ui: UI, index: Int)(using Frame): Unit < Async =
      buttons(ui).map(bs => bs(index).attrs.onClick.fold(Kyo.unit)(_.unit))
end UiTree
