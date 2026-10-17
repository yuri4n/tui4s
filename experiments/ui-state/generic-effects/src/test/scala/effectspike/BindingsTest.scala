package effectspike

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.UI.Ast.*
import kyo.test.*
import scala.compiletime.testing.typeCheckErrors

class BindingsTest extends Test[Any]:
    final case class Audit(events: SignalRef[Vector[Int]])

    enum SaveError derives CanEqual:
        case Rejected(index: Int)

    type Work = Async & Env[Audit] & Abort[SaveError]

    private def record(event: TabsEvent): Unit < (Async & Env[Audit]) =
      Env.use[Audit] { audit =>
        val TabsEvent.Change(index) = event: @unchecked
        Async.sleep(1.millis).andThen(audit.events.updateAndGet(_ :+ index).unit)
      }

    private def save(event: TabsEvent): Unit < Work =
      event match
          case TabsEvent.Change(2) =>
            Async.sleep(1.millis).andThen(Abort.fail(SaveError.Rejected(2)))
          case _ => record(event)

    /** App-specific effect discharge. This is ordinary Kyo, not library API. */
    private def forUi[E](
        handler: E => Unit < Work,
        audit: Audit,
        errors: SignalRef[Vector[SaveError]],
    ): E => Unit < Async =
      event =>
        Abort.run[SaveError](Env.run(audit)(Sync.defer(handler(event)))).map {
          case Result.Success(_)     => ()
          case Result.Failure(error) => errors.updateAndGet(_ :+ error).unit
          case Result.Panic(error)   => throw error
        }

    private def nodes(ui: UI): Vector[UI] < Sync =
      ui match
          case reactive: Reactive[?] => reactive.signal.current.map(nodes)
          case fragment: Fragment[?] => children(fragment.children)
          case element: Element => children(element.children).map(element +: _)
          case _ => Vector(ui)

    private def children(values: Chunk[UI]): Vector[UI] < Sync =
      Kyo.foreach(values)(nodes).map(_.flatten.toVector)

    private def click(ui: UI, index: Int): Unit < Async =
      nodes(ui).map(_.collect { case button: Button => button }(index).attrs.onClick.get.unit)

    "plain Async props preserve explicit constructors and existing annotations" in {
      for
          selected <- Signal.initRef(0)
          seen     <- Signal.initRef(Vector.empty[Int])
          emit: Emits[TabsEvent] = useEmits[TabsEvent] {
              case TabsEvent.Change(index) => seen.updateAndGet(_ :+ index).unit
          }
          model: Model[Int] = Model[Int](selected, next => selected.set(next))
          fromRef: Model[Int] = Model(selected)
          ui <- new Tabs(
                  Seq("One", "Two", "Three"),
                  model = Some(model),
                  onEvent = event => emit(event),
                ).ui
          _     <- click(ui, 1)
          value <- fromRef.value.current
          log   <- seen.get
      yield
          assert(value == 1)
          assert(log == Vector(1))
    }

    "generic constructors infer effects with and without explicit event/value types" in {
      for
          selected <- Signal.initRef(0)
          log      <- Signal.initRef(Vector.empty[Int])
          audit     = Audit(log)
          explicit  = useEffectEmits[TabsEvent](record)
          inferred  = useEffectEmits(record)
          model     = Model.effectful[Int](selected, next =>
                        record(TabsEvent.Change(next)).andThen(selected.set(next)),
                      )
          inferredModel = Model.effectful(selected, (next: Int) =>
                            record(TabsEvent.Change(next)).andThen(selected.set(next)),
                          )
          _ <- Env.run(audit)(explicit(TabsEvent.Change(1)))
          _ <- Env.run(audit)(inferred(TabsEvent.Change(2)))
          _ <- Env.run(audit)(model.set(3))
          _ <- Env.run(audit)(inferredModel.set(4))
          values <- log.get
          value  <- selected.get
      yield
          assert(values == Vector(1, 2, 3, 4))
          assert(value == 4)
    }

    "an Env callback can be resolved per app and used after the setup handler ends" in {
      def component: UI < (Sync & Env[Audit]) =
        Env.use[Audit] { audit =>
          val emit = useEffectEmits[TabsEvent](record)
          new Tabs(
            Seq("One", "Two", "Three"),
            onEvent = event => Env.run(audit)(emit(event)),
          ).ui
        }

      for
          a  <- Signal.initRef(Vector.empty[Int])
          b  <- Signal.initRef(Vector.empty[Int])
          ua <- Env.run(Audit(a))(component)
          ub <- Env.run(Audit(b))(component)
          _  <- click(ua, 1)
          _  <- click(ub, 2)
          av <- a.get
          bv <- b.get
      yield
          assert(av == Vector(1))
          assert(bv == Vector(2))
    }

    "combined Async Env and Abort effects are discharged before actual tab callbacks" in {
      for
          auditLog <- Signal.initRef(Vector.empty[Int])
          errors   <- Signal.initRef(Vector.empty[SaveError])
          emit: EffectEmits[TabsEvent, Work] = useEffectEmits[TabsEvent](save)
          ui <- new Tabs(
                  Seq("One", "Two", "Three"),
                  onEvent = forUi(event => emit(event), Audit(auditLog), errors),
                ).ui
          _      <- click(ui, 1)
          _      <- click(ui, 2)
          seen   <- auditLog.get
          failed <- errors.get
      yield
          assert(seen == Vector(1))
          assert(failed == Vector(SaveError.Rejected(2)))
    }

    "generic model rejects a typed save error and preserves authoritative value" in {
      for
          selected <- Signal.initRef(0)
          auditLog <- Signal.initRef(Vector.empty[Int])
          errors   <- Signal.initRef(Vector.empty[SaveError])
          effectModel = Model.effectful[Int](selected, next =>
                          save(TabsEvent.Change(next)).andThen(selected.set(next)),
                        )
          model: Model[Int] = Model[Int](
              effectModel.value,
              forUi(next => effectModel.set(next), Audit(auditLog), errors),
          )
          ui <- new Tabs(Seq("One", "Two", "Three"), model = Some(model)).ui
          _        <- click(ui, 1)
          accepted <- selected.get
          _        <- click(ui, 2)
          rejected <- selected.get
          failed   <- errors.get
      yield
          assert(accepted == 1)
          assert(rejected == 1)
          assert(failed == Vector(SaveError.Rejected(2)))
    }

    "the ordinary Async model adapts the same typed action without a generic model" in {
      for
          selected <- Signal.initRef(0)
          auditLog <- Signal.initRef(Vector.empty[Int])
          errors   <- Signal.initRef(Vector.empty[SaveError])
          model: Model[Int] = Model[Int](
              selected,
              forUi(next => save(TabsEvent.Change(next)).andThen(selected.set(next)), Audit(auditLog), errors),
          )
          ui <- new Tabs(Seq("One", "Two", "Three"), model = Some(model)).ui
          _        <- click(ui, 1)
          accepted <- selected.get
          _        <- click(ui, 2)
          rejected <- selected.get
          failed   <- errors.get
      yield
          assert(accepted == 1)
          assert(rejected == 1)
          assert(failed == Vector(SaveError.Rejected(2)))
    }

    "both generic bindings defer callback invocation and await asynchronous work" in {
      for
          calls  <- Sync.defer(AtomicInteger(0))
          value  <- Signal.initRef(0)
          log    <- Signal.initRef(Vector.empty[Int])
          emit    = useEffectEmits[TabsEvent] { event =>
                      val _ = calls.incrementAndGet()
                      record(event)
                    }
          model   = Model.effectful[Int](value, next =>
                      val _ = calls.incrementAndGet()
                      record(TabsEvent.Change(next)).andThen(value.set(next)),
                    )
          emission = emit(TabsEvent.Change(1))
          setting  = model.set(2)
          before  <- Sync.defer(calls.get())
          _       <- Env.run(Audit(log))(emission.andThen(setting))
          after   <- Sync.defer(calls.get())
          written <- value.get
          seen    <- log.get
      yield
          assert(before == 0)
          assert(after == 2)
          assert(written == 2)
          assert(seen == Vector(1, 2))
    }

    "handling expected save errors preserves asynchronous callback panics" in {
      for
          auditLog <- Signal.initRef(Vector.empty[Int])
          errors   <- Signal.initRef(Vector.empty[SaveError])
          thrown   <- Sync.defer(IllegalStateException("network failed"))
          handler: (TabsEvent => Unit < Work) = _ =>
              Async.sleep(1.millis).andThen(Sync.defer(throw thrown))
          ui <- new Tabs(
                  Seq("One", "Two", "Three"),
                  onEvent = forUi(handler, Audit(auditLog), errors),
                ).ui
          result <- Abort.run[Throwable](click(ui, 1))
          failed <- errors.get
      yield
          val same = result match
              case Result.Failure(error) => error eq thrown
              case _                     => false
          assert(same)
          assert(failed.isEmpty)
    }

    "the fixed Async aliases reject unresolved effects instead of erasing them" in {
      val eventErrors = typeCheckErrors("""
        import kyo.*
        import effectspike.*
        val emit: Emits[Int] = useEffectEmits[Int](_ => Env.get[String].unit)
      """)
      val modelErrors = typeCheckErrors("""
        import kyo.*
        import effectspike.*
        def binding(ref: SignalRef[Int]): Model[Int] =
          Model.effectful[Int](ref, _ => Abort.fail("rejected"))
      """)
      assert(eventErrors.nonEmpty)
      assert(modelErrors.nonEmpty)
    }
end BindingsTest
