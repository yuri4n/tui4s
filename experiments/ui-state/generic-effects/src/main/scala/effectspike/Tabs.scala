package effectspike

import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.UI.*

enum TabsEvent derives CanEqual:
    case Change(index: Int)

/** Renderer-independent Kyo UI tabs. Each evaluation of [[ui]] owns fresh
  * selection state unless a parent supplies a model. The panel receives the
  * selected index as a scoped slot, and `onEvent` receives selection requests.
  *
  * Runtime adapters can allocate [[initial]] when mounting, retain that
  * state during parent renders, and pass it to [[view]]. This class does not
  * impose a renderer's mounting or resource lifetime rules.
  * The constructor retains the caller's diagnostic `Frame`; later rendering
  * methods use that origin without requesting another contextual parameter.
  */
final class Tabs(using Frame)(
    labels: Seq[String],
    initialTab: Int = 0,
    model: Option[Model[Int]] = None,
    onEvent: TabsEvent => Unit < Async = _ => (),
    heading: UI = UI.empty,
    panel: Int => UI = _ => UI.empty,
):
    require(labels.nonEmpty, "Tabs needs at least one label")

    private def normalize(index: Int): Int =
      Math.floorMod(index, labels.size)

    /** Fresh local state with a distinct keyboard focus group per mount. */
    def initial: Tabs.State =
      Tabs.State(normalize(initialTab), s"kyo-ui-state-tabs-${Tabs.nextGroup.incrementAndGet()}")

    /** Builds a reactive UI using state owned by the caller or its renderer. */
    def view(state: SignalRef[Tabs.State]): UI =
        val binding = model.getOrElse(
          Model(
            state.map(_.selected),
            next => state.updateAndGet(_.copy(selected = next)).unit,
          ),
        )
        val selected = binding.value.map(normalize)
        val emit = useEmits(onEvent)

        def select(index: Int): Unit < Async =
          val next = normalize(index)
          binding.set(next).andThen(emit(TabsEvent.Change(next)))

        div(
          div.style(_.row.gap(2.px))(
            heading,
            state.map(_.focusGroup).render { group =>
              selected.render { current =>
                nav.style(_.gap(1.px))(
                  fragment(labels.zipWithIndex.map { (label, index) =>
                    button(label)
                      .focusGroup(group)
                      .onKeyDown {
                        case KeyboardEvent(Keyboard.ArrowLeft, _, _) =>
                          select(index - 1)
                        case KeyboardEvent(Keyboard.ArrowRight, _, _) =>
                          select(index + 1)
                        case _ => ()
                      }
                      .onClick(select(index))
                      .style(if index == current then
                          Style.bold.color(Style.Color.yellow)
                      else Style.color(Style.Color.gray))
                  }*),
                )
              }
            },
          ),
          selected.render(panel),
        )
    end view

    /** Allocates fresh local state once each time this effect executes. Keep
      * the returned UI during ordinary reactive updates; use [[initial]] and
      * [[view]] to integrate with a renderer's component mount lifecycle.
      */
    def ui: UI < Sync = Sync.defer(Signal.initRef(initial)).map(view)
end Tabs

object Tabs:
    final case class State(selected: Int, focusGroup: String) derives CanEqual

    private val nextGroup = new AtomicLong()
end Tabs
