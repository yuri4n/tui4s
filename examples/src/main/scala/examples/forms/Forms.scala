package examples.forms

// claude 2026-10-19: created (L1c): the native inputs of the terminal in one
// form. README.md "Forms and inputs" shows the lines between the two region
// lines, and ReadmeSuite checks that they are the same.
// region: forms
import kyo.*
import kyo.UI.*
import tui4s.{Tui, TuiApp, maxLength}

/** A sign-up form with the native inputs of kyo-ui: a text input, a password, a
  * number, a range, two checkboxes, two radios, a select, a textarea of 3 rows,
  * a read-only and a disabled field, and the Submit and Reset buttons. Tab
  * moves between the fields. Enter in a field submits the form (also in the
  * textarea: Alt-Enter or Ctrl-J there gives a new line). The result shows
  * under the form. Esc quits.
  */
object Forms:

    /** One row of the form: a label of 9 cells and its input (a `fragment` puts
      * more inputs side by side).
      */
    def field(label: String, input: UI): UI =
      div.style(_.row.gap(1.px).align(_.start))(
        span(label).style(_.width(9.px)),
        input,
      )

    /** The form; `quit` ends the run. */
    def ui(quit: Unit < Sync): UI < Sync =
      for
          name    <- Signal.initRef("")
          secret  <- Signal.initRef("")
          age     <- Signal.initRef("30")
          volume  <- Signal.initRef(50.0)
          news    <- Signal.initRef(true)
          updates <- Signal.initRef(false)
          plan    <- Signal.initRef("free")
          colour  <- Signal.initRef("blue")
          message <- Signal.initRef("")
          result  <- Signal.initRef("Fill the form, then press Enter.")
      yield
          val submit: Unit < Sync                      =
            for
                n <- name.get
                s <- secret.get
                a <- age.get
                v <- volume.get
                p <- plan.get
                c <- colour.get
                m <- message.get
                l <- news.get.map(on => if on then "news" else "no news")
                _ <- result.set(
                       s"Sent: $n, ${s.length} secret chars, age $a, " +
                         s"volume ${v.toInt}, $l, $p, $c, " +
                         s"${m.linesIterator.size} message lines",
                     )
            yield ()
          val reset: Unit < Sync                       =
            Kyo
              .foreach(Seq(name, secret, message))(_.set(""))
              .andThen(age.set("30"))
              .andThen(volume.set(50.0))
              .andThen(plan.set("free"))
              .andThen(result.set("The form is empty again."))
          // two radios of one group: the plan is the state; each radio shows
          // it and asks to change it
          def choice(value: String, label: String): UI =
            div.style(_.row.gap(1.px))(
              radio
                .onChange(_ => plan.set(value))
                .checked(plan.map(_ == value)),
              span(label),
            )
          div.onKeyDown {
            case KeyboardEvent(Keyboard.Escape, _, _) => quit
            case _                                    => ()
          }(
            h1("Sign up"),
            form.onSubmit(submit)(
              field("Name", input.value(name).placeholder("Ada").maxLength(20)),
              field("Secret", passwordInput.value(secret)),
              field("Age", numberInput.value(age).min(0).max(130).step(1)),
              field("Volume", rangeInput.value(volume).min(0).max(100).step(5)),
              field(
                "Mail",
                fragment(
                  checkbox.checked(news),
                  span("news"),
                  checkbox.checked(updates),
                  span("updates"),
                ),
              ),
              field(
                "Plan",
                fragment(choice("free", "Free"), choice("pro", "Pro")),
              ),
              field(
                "Colour",
                select.value(colour)(
                  option.value("blue")("Blue"),
                  option.value("green")("Green"),
                ),
              ),
              field(
                "Message",
                textarea.value(message).style(_.width(30.px).height(3.px)),
              ),
              field("Account", input.value("ada-01").readOnly(true)),
              field("Team", input.value("core").disabled(true)),
              div.style(_.row.gap(1.px))(
                button("Submit"),
                // kyo-ui has no button type: a button in a form submits it,
                // so Reset stops its click before the form
                button("Reset").onClick(reset).stopPropagation(true),
              ),
            ),
            p(result).id("result"),
          )
end Forms

/** Runs the form in the real terminal: `examples/target/bin/forms`. */
object FormsApp extends TuiApp:
    def ui: UI < (Async & Scope) = Forms.ui(Tui.quit)
// endregion: forms
