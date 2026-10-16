package spike

import kyo.*
import kyo.UI.*
import kyo.test.*
import pini4s.*
import scala.compiletime.testing.typeCheckErrors

/** Experiments only. All production sources are read without modification. */
class StoreEnvTest extends Test[Any]:
    private def envState[A](key: String)(initial: => A)(using
        Tag[A],
        CanEqual[A, A],
        Frame,
    ): SignalRef[A] < (Sync & Env[StateStore]) =
      Env.use[StateStore](_.useState(key)(initial))

    // Application helpers can omit Frame; the public macro supplies it.
    private def contextualCount(using StateStore): SignalRef[Int] < Sync =
      useState("count")(0)

    private def envCount: SignalRef[Int] < (Sync & Env[StateStore]) =
      envState("count")(0)

    private final case class Deferred(
        mount: () => SignalRef[Int] < Sync,
        select: Int => Unit < Async,
    )

    // Resolve the dependency before allocating callbacks. Capture a Scala
    // reference; no dependency effect escapes into the callback signatures.
    private def fromEnv: Deferred < Env[StateStore] =
      Env.use[StateStore] { store =>
        Deferred(
          () => store.useState("count")(0),
          next => store.useState("count")(0).map(_.set(next)),
        )
      }

    private def fromContext(using store: StateStore): Deferred =
      Deferred(
        () => useState("count")(0),
        next => useState("count")(0).map(_.set(next)),
      )

    "application helpers need no explicit Frame in either dependency style" in {
      for
          store <- StateStore.init
          first <- contextualCount(using store)
          next  <- Env.run(store)(envCount)
      yield assert(first eq next)
    }

    "spawn and join under one Env handler resolve the dependency" in {
      for
          store <- StateStore.init
          first <- store.useState("count")(4)
          next  <- Env.run(store)(Fiber.init(envCount).map(_.get))
          value <- next.get
      yield
          assert(first eq next)
          assert(value == 4)
    }

    "resolved Env dependency survives a later mount and event fiber" in {
      for
          store <- StateStore.init
          first <- store.useState("count")(4)
          hooks <- Env.run(store)(fromEnv)
          mount <- Fiber.init(hooks.mount())
          next  <- mount.get
          event <- Fiber.init(hooks.select(9))
          _     <- event.get
          value <- first.get
      yield
          assert(first eq next)
          assert(value == 9)
    }

    "contextual dependency survives a later mount and event fiber" in {
      for
          store <- StateStore.init
          first <- store.useState("count")(4)
          hooks  = fromContext(using store)
          mount <- Fiber.init(hooks.mount())
          next  <- mount.get
          event <- Fiber.init(hooks.select(8))
          _     <- event.get
          value <- first.get
      yield
          assert(first eq next)
          assert(value == 8)
    }

    "captured providers retain isolation after their Env handlers end" in {
      for
          one   <- StateStore.init
          two   <- StateStore.init
          a     <- Env.run(one)(fromEnv)
          b     <- Env.run(two)(fromEnv)
          _     <- a.select(3)
          _     <- b.select(7)
          ar    <- a.mount()
          br    <- b.mount()
          av    <- ar.get
          bv    <- br.get
      yield
          assert(av == 3)
          assert(bv == 7)
          assert(!(ar eq br))
    }

    "explicit provision inside an event adapts Env to the Async boundary" in {
      def action(index: Int): Unit < (Sync & Env[StateStore]) =
        envCount.map(_.set(index))

      for
          store <- StateStore.init
          emit = (next: Int) => (Env.run(store)(action(next)): Unit < Async)
          fiber <- Fiber.init(emit(6))
          _     <- fiber.get
          ref   <- store.useState("count")(0)
          value <- ref.get
      yield assert(value == 6)
    }

    "real Kyo UI action can capture a dependency resolved during setup" in {
      def ui: UI.Ast.Button < (Sync & Env[StateStore]) =
        envCount.map { ref =>
          val action: Unit < Async = ref.set(12)
          button("Select").onClick(action)
        }

      for
          store <- StateStore.init
          node  <- Env.run(store)(ui)
          _     <- node.attrs.onClick.get.unit
          ref   <- store.useState("count")(0)
          value <- ref.get
      yield assert(value == 12)
    }

    "raw Env dependency cannot escape into a typed Async callback" in {
      val errors = typeCheckErrors("""
        import kyo.*
        import pini4s.*
        val emit: Int => Unit < Async = next =>
          Env.use[StateStore](_.useState("count")(0).map(_.set(next)))
      """)
      assert(errors.nonEmpty)
    }

    "public onClick rejects an unresolved Env effect with its type intact" in {
      val errors = typeCheckErrors("""
        import kyo.*
        import kyo.UI.*
        import pini4s.*
        val action: Unit < (Sync & Env[StateStore]) =
          Env.use[StateStore](_.useState("count")(0).map(_.set(99)))
        val node = button("Raw Env action").onClick(action)
      """)
      assert(errors.nonEmpty)
    }

    "providing Env inside a fiber lets it be joined outside the handler" in {
      for
          store <- StateStore.init
          first <- store.useState("count")(4)
          fiber <- Fiber.init(Env.run(store)(envCount))
          next  <- fiber.get
      yield assert(first eq next)
    }

    "providing only the fiber spawn leaves Env required at join" in {
      val errors = typeCheckErrors("""
        import kyo.*
        import pini4s.*
        def lookup: SignalRef[Int] < (Sync & Env[StateStore]) =
          Env.use[StateStore](_.useState("count")(0))
        def invalid(store: StateStore): SignalRef[Int] < (Async & Scope) =
          Env.run(store)(Fiber.init(lookup)).map(_.get)
      """)
      assert(errors.nonEmpty)
    }

    "handling construction does not handle Env inside a returned thunk" in {
      val errors = typeCheckErrors("""
        import kyo.*
        def deferred: (() => Int < Async) < Sync =
          Env.run(1)(Sync.defer(() => Env.get[Int]))
      """)
      assert(errors.nonEmpty)
    }

    "unhandled Abort also cannot escape into a typed Async callback" in {
      val errors = typeCheckErrors("""
        import kyo.*
        val emit: Int => Unit < Async = _ => Abort.fail("failed")
      """)
      assert(errors.nonEmpty)
    }
end StoreEnvTest
