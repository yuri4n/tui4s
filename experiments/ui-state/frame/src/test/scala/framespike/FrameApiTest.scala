package framespike

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.test.*
import kyo.framespike.{CapturedView, Contextual, Environmental, InlineContextual, InlineEnvironmental}

class FrameApiTest extends Test[Any]:
    private def capture(using frame: Frame): Frame = frame

    private def text(ui: UI)(using Frame): String < Sync =
      ui match
          case reactive: Reactive[?] => reactive.signal.current.map(text)
          case fragment: Fragment[?] => children(fragment.children)
          case element: Element => children(element.children)
          case Text(value) => value
          case _ => ""

    private def children(values: Chunk[UI])(using Frame): String < Sync =
      Kyo.foreach(values)(text).map(_.mkString)

    "contextual calls capture distinct call sites; explicit override preserves its source" in {
      for
          ref <- Signal.initRef(0)
          first <- Contextual.set(ref, 1)
          second <- Contextual.set(ref, 2)
          seed = capture
          explicit <- Contextual.set(ref, 3)(using seed)
          value <- ref.get
      yield
          assert(value == 3)
          assert(first.position.lineNumber != second.position.lineNumber)
          assert(first.position.fileName == "FrameApiTest.scala")
          assert(explicit.position.show == seed.position.show)
          println(s"contextual first: ${first.callSite}")
          println(s"contextual second: ${second.callSite}")
          println(s"explicit override: ${explicit.callSite}")
    }

    "one Env handler supplies the same frame to distinct signal writes" in {
      for
          ref <- Signal.initRef(0)
          seed = capture
          values <- Env.run(seed) {
                      for
                          first <- Environmental.set(ref, 1)
                          second <- Environmental.set(ref, 2)
                      yield (first, second)
                    }
          value <- ref.get
      yield
          assert(value == 2)
          assert(values._1.position.show == seed.position.show)
          assert(values._2.position.show == seed.position.show)
          println(s"Env first/second: ${values._1.callSite} / ${values._2.callSite}")
    }

    "contextual and Env invocations both defer callbacks; Env must be handled for UI events" in {
      for
          calls <- Sync.defer(AtomicInteger(0))
          handler = ((_: Int) =>
                      val _ = calls.incrementAndGet()
                      Kyo.unit
                    ): (Int => Unit < Async)
          seed = capture
          direct = (Contextual.invoke(handler, 1).unit: Unit < Async)
          pending = (Environmental.invoke(handler, 2).unit: Unit < (Async & Env[Frame]))
          handled = (Env.run(seed)(pending): Unit < Async)
          ui = button("invoke").onClick(handled)
          before <- Sync.defer(calls.get())
          _ <- direct
          _ <- ui.asInstanceOf[Button].attrs.onClick.get.unit
          after <- Sync.defer(calls.get())
      yield
          assert(before == 0)
          assert(after == 2)
    }

    "contextual construction and effectful Env factory produce native reactive UI" in {
      val label = "OneTwo"
      for
          direct <- new CapturedView(label).ui
          seed = capture
          fromEnv <- Env.run(seed)(Environmental.view(label))
          directText <- text(direct)
          envText <- text(fromEnv)
      yield
          assert(directText == "OneTwo")
          assert(envText == directText)
    }

    "inline Env wrappers compile at app sites but retain the Env effect and supplied frame" in {
      for
          ref <- Signal.initRef(0)
          seed = capture
          seen <- Env.run(seed)(InlineEnvironmental.set(ref, 7))
          value <- ref.get
          ui <- Env.run(seed)(InlineEnvironmental.view("OneTwo"))
          rendered <- text(ui)
      yield
          assert(value == 7)
          assert(seen.position.show == seed.position.show)
          assert(rendered == "OneTwo")
    }

    "inline facade captures external call sites without an exposed Frame or Env parameter" in {
      for
          ref <- Signal.initRef(0)
          first <- InlineContextual.set(ref, 1)
          second <- InlineContextual.set(ref, 2)
          ui <- InlineContextual.view("OneTwo")
          rendered <- text(ui)
      yield
          assert(first.position.fileName == "FrameApiTest.scala")
          assert(first.position.lineNumber != second.position.lineNumber)
          assert(rendered == "OneTwo")
          println(s"inline first: ${first.callSite}")
          println(s"inline second: ${second.callSite}")
    }

    "captured declaration origin survives rendering from another source file without method givens" in {
      val widget = new CapturedView("OneTwo")
      for
          receipt <- CapturedCaller.render(widget)
          rendered <- text(receipt._1)
      yield
          assert(rendered == "OneTwo")
          assert(receipt._2.position.fileName == "FrameApiTest.scala")
          assert(receipt._3.position.fileName == "CapturedCaller.scala")
          println(s"captured origin: ${receipt._2.callSite}; invoked from: ${receipt._3.callSite}")
    }
end FrameApiTest
