package examples.composables

import kyo.*
import kyo.UI.*
import tui4s.use.*

/** One small UI for each composable of tui4s-use that the Todo and the
  * Dashboard do not use. The README section "More composables" shows each
  * region. A library with no main; RecipesSuite runs each UI on the headless
  * test kit.
  */
// claude 2026-10-19: created (L2a review: an example for each composable)
object Recipes:

    // region: search
    /** A search as the user types: `debounced` waits until the query is quiet
      * for 200 ms, and `useAsync` runs `find` for it. A new query interrupts
      * the search for the old one.
      */
    def search(find: String => Chunk[String] < Async)(using
        Frame,
    ): UI < (Sync & Scope) =
      for
          query   <- Signal.initRef("")
          quiet   <- debounced(query, 200.millis)
          results <- useAsync(quiet.map(q => Maybe.when(q.nonEmpty)(q)))(find)
      yield div(
        input.value(query).focusAuto(true),
        results.map {
          case AsyncState.Idle       => span("type to search")
          case AsyncState.Loading    => span("searching...")
          case AsyncState.Done(rows) => span(s"found: ${rows.mkString(", ")}")
          case AsyncState.Failed(e)  => span(s"failed: $e")
        },
      )
    // endregion: search

    // region: progress
    /** A progress that changes faster than the screen needs: `throttled` shows
      * it at most once per 100 ms, and the last value always shows.
      */
    def progress(done: Signal[Int])(using Frame): UI < (Sync & Scope) =
      for shown <- throttled(done, 100.millis)
      yield span(shown.map(n => s"$n %"))
    // endregion: progress

    // region: autosave
    /** A note that saves itself: each edit starts the wait again, and `save`
      * runs 300 ms after the last edit, with the last text.
      */
    def autosave(save: String => Unit < Async)(using
        Frame,
    ): UI < (Sync & Scope) =
      for
          note   <- Signal.initRef("")
          status <- Signal.initRef("saved")
          later  <- useDebouncedFn[String](300.millis)(text =>
                      save(text).andThen(status.set("saved")),
                    )
      yield div(
        input
          .value(note)
          .focusAuto(true)
          .onInput(text => status.set("editing").andThen(later(text))),
        span(status),
      )
    // endregion: autosave

    // region: answer
    /** An answer that comes in pieces (the tokens of a chat model):
      * `useStreamText` joins them, and its status says when the answer is
      * complete.
      */
    def answer(tokens: Stream[String, Async])(using
        Frame,
    ): UI < (Sync & Scope) =
      for reply <- useStreamText(tokens)
      yield div(
        span(reply.value),
        span(reply.status.map {
          case StreamStatus.Running   => "..."
          case StreamStatus.Done      => "(complete)"
          case StreamStatus.Failed(e) => s"(failed: ${e.getMessage})"
        }),
      )
    // endregion: answer

    // region: list
    /** A long list that shows 5 rows: `useSelection` keeps the selected row by
      * its key, and `useScrollWindow` gives the rows to show. The arrows move
      * the selection.
      */
    def list(items: Signal[Chunk[String]])(using Frame): UI < (Sync & Scope) =
      for
          selection <- useSelection(items, identity[String])
          window    <- useScrollWindow(items, Signal.initConst(5), selection.index)
          keys       = Keymap.of(
                         Binding.of("up", Chord(Keyboard.ArrowUp))(selection.moveUp),
                         Binding.of("down", Chord(Keyboard.ArrowDown))(
                           selection.moveDown,
                         ),
                       )
      yield div
        .tabIndex(0)
        .focusAuto(true)
        .onKeyDown(keys.handler)(
          window
            .combineLatest(selection.key)
            .map((w, key) =>
              div(
                UI.fragment(
                  w.slice.map(row =>
                    span(if key.contains(row) then s"> $row" else s"  $row"),
                  )*,
                ),
              ),
            ),
        )
    // endregion: list

    // region: confirm
    /** A delete that asks first: `d` starts a flow (`launch`) that opens the
      * dialog and waits for its answer; `y` or `n` answers.
      */
    def confirm(items: SignalRef[Chunk[String]])(using
        Frame,
    ): UI < (Sync & Scope) =
      for
          ask   <- useDialog[String, Boolean]
          delete = items.get.map(xs =>
                     if xs.isEmpty then Kyo.unit
                     else
                         ask
                           .open(s"Delete ${xs.head}?")
                           .map(yes =>
                             if yes then items.updateAndGet(_.drop(1)).unit
                             else Kyo.unit,
                           ),
                   )
          keys   = Keymap.of(
                     Binding.of("delete", Chord.char('d'))(ask.launch(delete)),
                     Binding.of("yes", Chord.char('y'))(ask.close(true)),
                     Binding.of("no", Chord.char('n'))(ask.close(false)),
                   )
      yield div
        .tabIndex(0)
        .focusAuto(true)
        .onKeyDown(keys.handler)(
          span(items.map(_.mkString(", "))),
          span(ask.request.map(_.fold("")(q => s"$q (y/n)"))),
        )
    // endregion: confirm

    // region: setting
    /** A setting that the next run keeps: `usePersisted` reads it from
      * `storage` and writes each change. `t` changes it. An app gives
      * `Storage.forApp("<app>")`; a test gives `Storage.inMemory`.
      */
    def setting(storage: Storage)(using Frame): UI < (Sync & Scope) =
      for
          dark <- usePersisted("dark", false, storage)
          keys  = Keymap.of(
                    Binding.of("theme", Chord.char('t'))(dark.updateAndGet(!_)),
                  )
      yield div
        .tabIndex(0)
        .focusAuto(true)
        .onKeyDown(keys.handler)(
          span(dark.map(d => if d then "theme: dark" else "theme: light")),
        )
    // endregion: setting

    // region: process
    /** The output of a program, a line at a time, and its exit code:
      * `useProcess` starts it, and the end of the `Scope` kills it.
      */
    def process(cmd: Chunk[String])(using Frame): UI < (Sync & Scope) =
      Abort.run[ProcessError](useProcess(cmd)).map {
        case Result.Success(run) =>
          useStreamFold(run.lines, Chunk.empty[String])((ls, l) =>
            (ls :+ l).takeRight(5),
          ).map(out =>
            div(
              span(out.value.map(_.mkString(" | "))),
              span(run.exit.map(_.fold("running")(c => s"exit ${c.toInt}"))),
            ),
          )
        case Result.Failure(e)   => span(s"cannot start: $e")
        case Result.Panic(t)     => span(s"cannot start: $t")
      }
    // endregion: process
end Recipes
