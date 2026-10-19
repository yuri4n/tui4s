package examples.gallery

import kyo.*
import kyo.UI.*
import tui4s.use.{Binding, Chord, Keymap, Selection, useSelection}
import tui4s.widgets.*
import tui4s.{Tui, TuiApp}

/** The gallery of tui4s-widgets: a list of the widget families at the left and
  * every widget of the chosen family at the right, with live state. The arrows
  * on the list (or F1 to F5) choose a family; Tab goes from widget to widget;
  * the key hints at the bottom come from the keymap of the app; Ctrl-P opens
  * the command palette over that keymap; Ctrl-Q quits. Each family is one
  * function, and the README shows each one (the regions).
  */
// claude 2026-10-19: created (L2b)
object Gallery:

    /** The widget families: the items of the list at the left. */
    enum Family derives CanEqual:
        case Containers, Data, Input, Feedback, Navigation

    // region: containers
    /** The containers: a panel, a split pane, an accordion and tabs. */
    def containers(using Frame): UI < Sync =
      for
          split <- SplitPane(
                     Panel(title = "Left")(span("files")),
                     Panel(title = "Right")(span("preview")),
                   )(size = Model.Local(40))
          acc   <- Accordion(
                     Accordion.Section("General", span("Name and email.")),
                     Accordion.Section("Advanced", span("Cache and logs.")),
                   )()
          tabs  <- Tabs("One", "Two", "Three")(
                     panel = i => span(s"The panel of tab ${i + 1}."),
                   ).ui
      yield div.style(_.gap(1.px))(
        Panel(title = "Panel")(span("A border style and a title on it.")),
        labeled("SplitPane (Tab to the divider, then ←/→)", split),
        labeled("Accordion", acc),
        labeled("Tabs", tabs),
      )
    // endregion: containers

    /** A language of the table and of the autocomplete. */
    final case class Lang(name: String, year: Int, kind: String)

    val langs: Chunk[Lang] = Chunk(
      Lang("Scala", 2004, "functional and object"),
      Lang("Haskell", 1990, "functional"),
      Lang("Kotlin", 2011, "object"),
      Lang("OCaml", 1996, "functional"),
    )

    // region: data
    /** The data widgets: the charts, a list, a table, a tree and a pager. */
    def data(using Frame): UI < (Sync & Scope) =
      for
          fruits  <-
            Signal.initRef(Chunk("apple", "banana", "cherry", "date", "elder"))
          list    <- ListView(fruits, identity[String], height = 3)(span(_))
          table   <- DataTable(
                       Signal.initConst(langs),
                       _.name,
                       DataTable.Column("Name", _.name, DataTable.Width.Fixed(8)),
                       DataTable.Column(
                         "Year",
                         _.year.toString,
                         order = Present(Ordering.by(_.year)),
                       ),
                       DataTable.Column("Kind", _.kind, DataTable.Width.Fill),
                     )(height = 3)
          tree    <- TreeView(
                       Signal.initConst(
                         Chunk(
                           TreeView.Node(
                             "src",
                             Chunk(
                               TreeView.Node("Main.scala"),
                               TreeView.Node("Ui.scala"),
                             ),
                           ),
                           TreeView.Node("README.md"),
                         ),
                       ),
                       identity[String],
                       height = 4,
                     )(span(_))
          log     <- Pager(
                       Signal.initConst(
                         Chunk.from((1 to 40).map(i => s"event $i: served")),
                       ),
                       height = 3,
                     )
          spinner <- Spinner(label = "working")
      yield div.style(_.gap(1.px))(
        div.style(_.row.gap(2.px))(
          Sparkline(Signal.initConst(Chunk(0.1, 0.4, 0.9, 0.6, 0.3))),
          Gauge(Signal.initConst(0.42), width = 10),
          ProgressBar(Signal.initConst(3), Signal.initConst(10), width = 10),
          spinner,
        ),
        BarChart(
          Signal.initConst(Chunk("Mon" -> 3.0, "Tue" -> 7.5, "Wed" -> 5.0)),
          width = 20,
        ),
        labeled("ListView (↑/↓, PgUp/PgDn, Home/End)", list),
        labeled("DataTable (1 to 3 sort)", table),
        labeled("TreeView (→ opens, ← closes)", tree),
        labeled("Pager (f follows, n/N next match)", log),
      )
    // endregion: data

    // region: input
    /** The input widgets: a search box, an autocomplete, a checklist, a radio
      * group, a number stepper and a date field.
      */
    def input(using Frame): UI < (Sync & Scope) =
      for
          search <- SearchBox()
          auto   <- Autocomplete(
                      Signal.initConst(langs.map(_.name)),
                      matcher = Autocomplete.Match.Fuzzy,
                      placeholder = "a language",
                    )
          tasks  <- Checklist(
                      Signal.initConst(
                        Chunk("write the widgets", "write the tests"),
                      ),
                      identity[String],
                      height = 2,
                    )(span(_))
          size   <- RadioGroup("Small", "Medium", "Large")()
          count  <- NumberStepper(value = Model.Local(3), min = 0, max = 10)
          date   <- DateField()
      yield div.style(_.gap(1.px))(
        labeled(
          "SearchBox",
          div(search.view, span(search.query.map(q => s"query: $q"))),
        ),
        labeled("Autocomplete (↓, Enter)", auto),
        labeled("Checklist (Space)", tasks),
        labeled("RadioGroup (↑/↓)", size),
        labeled("NumberStepper (↑/↓, PgUp/PgDn)", count),
        labeled("DateField", date),
      )
    // endregion: input

    /** The layers of the app: the root holds their views, so they cover the
      * screen whatever the family.
      */
    final case class Layers(
        palette: CommandPalette,
        confirm: Modal[Boolean],
        prompt: Modal[Maybe[String]],
        toasts: Toasts,
    )

    // region: feedback
    /** The feedback widgets: a status bar, key hints, the command palette, a
      * confirm, a prompt and toasts.
      */
    def feedback(layers: Layers)(using Frame): UI < Sync =
      for
          answer <- Signal.initRef("no answer yet")
          name   <- Signal.initRef("notes.txt")
      yield div.style(_.gap(1.px))(
        labeled(
          "StatusBar",
          StatusBar(
            Signal.initConst("gallery.scala  120 lines"),
            right = span("UTF-8"),
          ),
        ),
        labeled(
          "KeyHints",
          KeyHints.of(Chunk("↑/k" -> "up", "↓/j" -> "down", "Enter" -> "open")),
        ),
        labeled(
          "CommandPalette",
          div.style(_.row)(
            button("Open the palette (Ctrl-P)").onClick(layers.palette.open),
          ),
        ),
        labeled(
          "Modal.confirm and Modal.prompt",
          div.style(_.row.gap(2.px))(
            button("Delete").onClick(
              layers.confirm.ask("Delete the file?")(yes =>
                answer.set(if yes then "deleted" else "kept"),
              ),
            ),
            span(answer),
            button("Rename").onClick(
              layers.prompt.ask("New name?")(n => n.fold(Kyo.unit)(name.set)),
            ),
            span(name.map(n => s"name: $n")),
          ),
        ),
        labeled(
          "Toasts",
          div.style(_.row)(
            button("Show a toast").onClick(
              layers.toasts.show("Saved", Toasts.Level.Success),
            ),
          ),
        ),
      )
    // endregion: feedback

    // region: navigation
    /** The navigation widgets: a menu bar, a tooltip, breadcrumbs, pagination,
      * tags and a wizard.
      */
    def navigation(using Frame): UI < Sync =
      for
          done  <- Signal.initRef("nothing chosen")
          file   = Keymap.of(
                     Binding.of("New", Chord.ctrl('n'))(done.set("File › New")),
                     Binding.of("Open", Chord.ctrl('o'))(done.set("File › Open")),
                   )
          help   =
            Keymap.of(
              Binding.of("About", Chord(Keyboard.F12))(done.set("Help › About")),
            )
          menu  <- Menu.bar(Menu.Group("File", file), Menu.Group("Help", help))
          tip   <- Tooltip("Saves the file")(button("Save"))
          path  <- Signal.initRef(Chunk("home", "docs", "widgets"))
          crumb <-
            Breadcrumbs(path, onPick = i => path.updateAndGet(_.take(i + 1)))
          page  <- Signal.initRef(0)
          pages <- Pagination(Signal.initConst(12), page = Model.Bound(page))
          tags  <- Tags(tags = Model.Local(Chunk("scala", "kyo")))
          steps <- Wizard(
                     Wizard.Step("Account", span("Pick a user name.")),
                     Wizard.Step("Profile", span("Add a photo.")),
                     Wizard.Step("Done", span("All set.")),
                   )(onFinish = done.set("wizard finished"))
      yield div.style(_.gap(1.px))(
        labeled("Menu (↓ opens, Enter runs)", div(menu, span(done))),
        labeled("Tooltip (on focus)", tip),
        labeled("Breadcrumbs", crumb),
        labeled(
          "Pagination (←/→)",
          div(pages, span(page.map(p => s"page ${p + 1} of 12"))),
        ),
        labeled("Tags (Enter adds, Delete removes)", tags),
        labeled("Wizard", steps),
      )
    // endregion: navigation

    /** A widget under its name. */
    def labeled(name: String, widget: UI)(using Frame): UI =
      div(span(name).style(_.bold.color(Colors.muted)), widget)

    /** The function keys of the families: F1 to F5. */
    val familyKeys: Chunk[Keyboard] =
      Chunk(Keyboard.F1, Keyboard.F2, Keyboard.F3, Keyboard.F4, Keyboard.F5)

    /** The commands of the app: the palette lists them, and the hints at the
      * bottom show them with the key of the palette.
      */
    def commands(families: Selection[Family, Family], toasts: Toasts)(using
        Frame,
    ): Keymap =
      Keymap.of(
        (Chunk
          .from(Family.values)
          .zip(familyKeys)
          .map((f, k) =>
            Binding.of(f.toString.toLowerCase, Chord(k))(families.select(f)),
          ) ++ Chunk(
          Binding.of("toast", Chord.ctrl('t'))(
            toasts.show("Hello from the keymap"),
          ),
          Binding.of("quit", Chord.ctrl('q'))(Tui.quit),
        ))*,
      )

    /** The app: the family list, the panel of the chosen family, the hints, and
      * the layers.
      */
    def app(using Frame): UI < (Async & Scope) =
      for
          families <-
            Signal.initRef(Chunk.from(Family.values.toSeq): Chunk[Family])
          chosen   <- useSelection(families, identity[Family])
          toasts   <- Toasts(lifetime = 2.seconds)
          confirm  <- Modal.confirm()
          prompt   <- Modal.prompt()
          cmds      = commands(chosen, toasts)
          palette  <- CommandPalette(cmds)
          layers    = Layers(palette, confirm, prompt, toasts)
          keys      = Keymap.of(
                        (cmds.bindings :+ Binding.of("palette", Chord.ctrl('p'))(
                          palette.open,
                        ))*,
                      )
          c        <- containers
          d        <- data
          i        <- input
          f        <- feedback(layers)
          n        <- navigation
          panels    = Chunk(c, d, i, f, n)
          list     <-
            ListView.of(families, chosen, height = 5)(f => span(f.toString))
      yield div
        .onKeyDown(keys.handler)
        .style(_.height(100.pct))(
          span("tui4s widgets").style(_.bold.color(Colors.accent)),
          div.style(_.row.flexGrow(1).overflow(_.hidden).gap(1.px))(
            div.style(_.width(18.px).flexShrink(0))(
              Panel(title = "Families")(list),
            ),
            div.style(_.flexGrow(1).overflowY(_.auto))(
              chosen.index.render(panels(_)),
            ),
          ),
          KeyHints(keys),
          palette.view,
          confirm.view,
          prompt.view,
          toasts.view,
        )
end Gallery

/** Runs the gallery in the real terminal, with the colours of the widgets
  * (`Colors.defaults`). Start it with its start script:
  * {{{
  * sbt examples/launchers
  * examples/target/bin/gallery
  * }}}
  */
// claude 2026-10-19: created (L2b)
object GalleryApp extends TuiApp:
    override def stylesheet: Stylesheet = Colors.defaults
    def ui: UI < (Async & Scope)        = Gallery.app
