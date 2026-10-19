# A runner outside `kyo` cannot honour `UI.scrollIntoView`

Draft for <https://github.com/getkyo/kyo/issues> (module `kyo-ui`). Written
2026-10-19 by the tui4s maintainers. tui4s is a terminal runner for kyo-ui: it
lays out a `UI` value in terminal cells and dispatches keys.

## Summary

`UI.scrollIntoView(id)` is public, but the only way to receive it is the
session sink `kyo.internal.UICommands.scrollSink`, which is `private[kyo]`. A
runner that is not in package `kyo` (a terminal runner, a test kit, a native
UI) cannot install a sink, so a handler that calls `UI.scrollIntoView` is a
no-op there, with no error. We ask for a public way to receive the request, or
for a declarative attribute that a runner can read.

## Version

- `io.getkyo::kyo-ui:1.0.0-RC7` (JVM and Scala.js)

## What happens (from the sources of 1.0.0-RC7)

1. `UI.scrollIntoView(id)` calls `UICommands.scrollIntoView(id)`.
2. `UICommands` is `private[kyo] object UICommands`, and its
   `scrollSink: Local[Maybe[String => Unit < Async]]` is `private[kyo]`.
3. `UIServer` and `DomBackend` (both in `kyo.internal`) bind the sink for their
   session; outside a session the call does nothing.
4. A runner outside `kyo` cannot bind `scrollSink`: the compiler rejects the
   access, and the only way around it (a source file in package `kyo`, or
   reflection) is not an option for a library that uses only public APIs.

## What tui4s does today

tui4s implements the browser's focus rule itself (the CSSOM "scroll into view"
with `block: nearest`):

- An element with `overflow` (or `overflowX`, `overflowY`) `scroll` or `auto`
  is a viewport that scrolls; `hidden` clips and does not scroll.
- When the focus moves, each viewport that holds the focused element scrolls
  the least distance that shows the element's border box, the innermost first.
  A box that fits shows whole; a box larger than the viewport covers it.
- After a key or a paste on the focused element (a text input that the user
  types in), the same rule keeps it in view.
- A list that keeps the focus and moves a selection names the selected element
  with `aria("activedescendant", id)`; the viewport follows that element. This
  is the WAI-ARIA pattern, and it is the only declarative way today for an app
  to say "show this element" to tui4s.

So an app can show the focused element, but it cannot ask for any other
element: `UI.scrollIntoView("row-42")` from a handler has no effect in the
terminal, while the same code scrolls in the browser.

## What would help (any one of these)

1. A public sink: for example `UI.withScrollSink(sink: String => Unit <
   Async)(v: A < S): A < S`, or a public `Local` that a runner binds around the
   handlers of its session. The rest of `UICommands` can stay internal.
2. A public command stream for the custom transports that `UI.runRender` already
   serves ("useful for testing, SSR, export, or custom transports"): the
   imperative commands of a session (`scrollIntoView` today) as values that a
   runner reads.
3. A declarative attribute or style that a runner can read from the AST, for
   example `scrollIntoViewOnRender` or `scrollTarget(true)`: "keep this element
   in view when it renders". A runner then needs no sink, and the request is
   visible in the AST (`Attrs` already has `focusAuto` for the same kind of
   one-shot intent).

With 1 or 2, tui4s would call its follow rule with the element that has the
id; with 3, it would treat the attribute as one more scroll target, next to
the focus and `aria-activedescendant`.

## Related

- `preventScrollKeys` (`data-kyo-scroll-keys`) is readable through
  `Element.attrs`, and tui4s honours it: the navigation keys do not scroll in
  such a region. That works because it is declarative; `scrollIntoView` is not.
