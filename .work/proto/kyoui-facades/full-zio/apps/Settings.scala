package apps.zio.full

import tui4s.interop.zio.ui.*
import zio.*

/** A settings form over elements that only the full mirror has (main, header, nav, a, form, label, textarea, range,
  * file input, select, option, table, img, hr, footer). No Kyo type, no Kyo import.
  */
// claude 2026-10-03: created a user program over the full mirror, to check that the 52-element mirror is usable
object SettingsApp:
    val app: ZIO[Scope, Nothing, HtmlNode] =
        for
            name   <- useSignal("")
            bio    <- useSignal("")
            volume <- useSignal(50.0)
            dark   <- useSignal(false)
            theme  <- useSignal("Light")
            file   <- useSignal("")
            saved  <- useSignal(List.empty[String])
        yield
            val save: UIO[Unit] =
                for
                    n <- name.get
                    b <- bio.get
                    v <- volume.get
                    d <- dark.get
                    t <- theme.get
                    _ <- saved.update(_ :+ s"$n|$b|${v.toInt}|$d|$t")
                yield ()

            main(
                header(h1("Settings").style(_.bold.padding(1.em))),
                nav(a.href(Href.Path("/help"), Target.Blank)("Help")),
                form(
                    label.forId("name")("Name"),
                    input.id("name").value(name),
                    textarea.id("bio").value(bio).placeholder("About you"),
                    rangeInput.id("volume").min(0).max(100).step(5).value(volume),
                    checkbox.id("dark").checked(dark),
                    fileInput.id("avatar").accept(FileAccept.Image(ImageExt.Png), FileAccept.Pdf).onChange(file.set),
                    select.id("theme").value(theme)(option.value("Light")("Light"), option.value("Dark")("Dark")),
                    button("Save").id("save")
                ).onSubmit(save),
                table(tbody(tr(td("Volume"), td(volume.signal.map(v => s"${v.toInt}"))))),
                ul(saved.signal.foreach(s => li(s"saved: $s"))),
                hr,
                img(ImgSrc.Path("logo.png"), "logo"),
                footer(p("v1"))
            )
end SettingsApp
