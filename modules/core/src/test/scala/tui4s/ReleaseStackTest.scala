package tui4s

import kyo.*
import kyo.test.*
import tui4s.internal.ReleaseStack

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import scala.jdk.CollectionConverters.*

/** The release stack of a run (A1): a model test of its contract. Each release
  * runs once, the last push first; a failing step does not stop the next steps,
  * and the first failure is reported once; two threads that call `runAll` at
  * the same time run the stack once; a push to a closed stack runs at once; no
  * release runs under the monitor of the stack; an interrupt does not cut the
  * release short.
  */
// claude 2026-10-19: created (P3, A1)
class ReleaseStackTest extends Test[Any]:

    /** One round of the model: `n` steps, step `k` fails, two threads call
      * `runAll` at the same time, then this thread calls it again and pushes
      * one more step. Gives the differences from the model (empty: none).
      */
    private def round(id: Int, random: java.util.Random): Seq[String] =
        val n       = 1 + random.nextInt(12)
        val k       = 1 + random.nextInt(n)
        val linkage = random.nextBoolean()
        val ran     = ConcurrentLinkedQueue[Int]()
        val traced  = ConcurrentLinkedQueue[String]()
        val reports = ConcurrentLinkedQueue[Throwable]()
        val locked  = ConcurrentLinkedQueue[Int]()
        val stack   = ReleaseStack(
          t =>
              val _ = reports.add(t)
          ,
          s =>
              val _ = traced.add(s),
        )
        (1 to n).foreach { i =>
          stack.add(s"step $i") { () =>
            val _ = ran.add(i)
            if Thread.holdsLock(stack) then
                val _ = locked.add(i)
            if i == k then
                if linkage then throw UnsatisfiedLinkError(s"step $i failed")
                else throw IllegalStateException(s"step $i failed")
          }
        }
        val start   = CountDownLatch(1)
        val callers = Seq.fill(2)(Thread { () =>
          start.await()
          stack.runAll()
        })
        callers.foreach(_.start())
        start.countDown()
        callers.foreach(_.join(10_000))
        stack.runAll()
        stack.add("late")(() =>
            val _ = ran.add(0),
        )
        val order   = ran.asScala.toVector
        val failed  = reports.asScala.toVector.map(_.getMessage)
        Seq(
          Option.when(order != (n to 1 by -1).toVector :+ 0)(
            s"round $id: the releases ran $order (n = $n, k = $k)",
          ),
          Option.when(failed != Vector(s"step $k failed"))(
            s"round $id: the reports are $failed (k = $k)",
          ),
          Option.when(
            traced.asScala.toVector !=
              (n to 1 by -1).map(i => s"step $i").toVector :+ "late",
          )(s"round $id: the trace is ${traced.asScala.toVector}"),
          Option.when(!locked.isEmpty)(
            s"round $id: steps ${locked.asScala.toVector} ran under the monitor",
          ),
        ).flatten
    end round

    "100 rounds: each release runs once, the last push first, also with two concurrent runAll calls and a failing step" in {
      val random   = java.util.Random(20261019L)
      val problems = (1 to 100).flatMap(round(_, random))
      assert(problems.isEmpty, problems.mkString("\n"))
    }

    // The negative control of the bug class "the release runs twice": with a
    // runAll that does not close the stack, this leaf fails.
    "a second runAll does nothing: the releases ran once" in {
      val ran   = ConcurrentLinkedQueue[Int]()
      val stack = ReleaseStack(_ => ())
      (1 to 3).foreach(i =>
        stack.add(s"step $i")(() =>
            val _ = ran.add(i),
        ),
      )
      stack.runAll()
      stack.runAll()
      assert(ran.asScala.toVector == Vector(3, 2, 1), ran.toString)
    }

    "push to a closed stack runs the release at once" in {
      val ran   = ConcurrentLinkedQueue[String]()
      val stack = ReleaseStack(_ => ())
      stack.runAll()
      stack
        .push("late")(() =>
            val _ = ran.add("late"),
        )
        .map(_ => assert(ran.asScala.toVector == Vector("late"), ran.toString))
    }

    "an interrupt does not cut the release short; the flag is set again after the last step" in {
      val seen   = ConcurrentLinkedQueue[String]()
      val stack  = ReleaseStack(_ => ())
      stack.add("first")(() =>
          val _ = seen.add(s"first ${Thread.currentThread().isInterrupted}"),
      )
      stack.add("interrupted")(() => throw InterruptedException("stop"))
      stack.add("last")(() =>
          val _ = seen.add(s"last ${Thread.currentThread().isInterrupted}"),
      )
      val result = ConcurrentLinkedQueue[Boolean]()
      val closer = Thread { () =>
        Thread.currentThread().interrupt()
        stack.runAll()
        val _ = result.add(Thread.currentThread().isInterrupted)
      }
      closer.start()
      closer.join(10_000)
      assert(
        seen.asScala.toVector == Vector("last false", "first false"),
        seen.toString,
      )
      assert(result.asScala.toVector == Vector(true), result.toString)
    }

    "a release can wait for a thread that pushes to the same stack (no release holds the monitor)" in {
      val ran   = ConcurrentLinkedQueue[String]()
      val stack = ReleaseStack(_ => ())
      stack.add("waits") { () =>
        val other = Thread(() =>
          stack.add("from another thread")(() =>
              val _ = ran.add("pushed"),
          ),
        )
        other.start()
        other.join(10_000)
        val _     = ran.add(s"joined ${!other.isAlive}")
      }
      stack.runAll()
      assert(
        ran.asScala.toVector == Vector("pushed", "joined true"),
        ran.toString,
      )
    }
end ReleaseStackTest
