package examples.assistant

import kyo.*
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.typedarray.{Int8Array, Uint8Array, int8Array2ByteArray}

/** The HTTP seam of the browser ([[HttpSeam]]) on `fetch`: one POST with a text
  * body; the body of the answer comes from its `ReadableStream` reader, cut
  * into lines by [[Sse.Lines]]. A status that is not 2xx fails with
  * [[HttpFailure.Status]] (with its `retry-after` header and at most
  * [[maxErrorBody]] characters of its body); a rejected `fetch` or read fails
  * with [[HttpFailure.Transport]]. The wait for the headers (and for the body
  * of an error status) is at most `timeout`, as on the JVM ([[KyoHttp]]);
  * after the headers, a body that gives no bytes for `idle` fails with
  * [[HttpFailure.Transport]].
  *
  * Each call has its own `AbortController` in a `Scope`: the end of the reader
  * (also an interrupt: Esc, or the end of the app) aborts the request, and the
  * browser closes the connection. The request does not follow a redirect
  * (`redirect: "error"`, so the key goes only to the host of the call), sends
  * no cookie (`credentials: "omit"`), no referrer, and is not cached.
  */
// claude 2026-10-19: created (L4); changed: the limit of the wait for the
// headers (L4 review)
object FetchHttp:
    /** A `fetch`: the browser's ([[browser]]), or a fake in a test. */
    type Fetch = (String, dom.RequestInit) => js.Promise[dom.Response]

    /** The `fetch` of the browser. */
    val browser: Fetch = (url, init) => dom.fetch(url, init)

    /** The longest error body that the seam keeps, in characters. */
    val maxErrorBody: Int = 64 * 1024

    /** The seam on `fetch`: at most `timeout` for the headers, and at most
      * `idle` between two pieces of the body.
      */
    // claude 2026-10-19: changed: the limit of the wait for the headers (L4
    // review)
    def seam(
        fetch: Fetch = browser,
        timeout: Duration = 60.seconds,
        idle: Duration = 5.minutes,
    ): HttpSeam =
      new HttpSeam:
          def lines(call: HttpCall)(using
              Frame,
          ): Stream[String, Async & Abort[HttpFailure]] =
            Stream {
              Scope.run(
                Scope
                  .acquireRelease(Sync.defer(new dom.AbortController()))(c =>
                    Sync.defer(c.abort()),
                  )
                  .map(controller =>
                    within(timeout, "the headers")(
                      await(fetch(call.url, init(call, controller.signal))),
                    )
                      .map(r =>
                        if r.ok then read(r, idle)
                        else
                            errorBody(r, timeout).map(body =>
                              Abort.fail(
                                HttpFailure.Status(
                                  r.status,
                                  Maybe(r.headers.get("retry-after")),
                                  body,
                                ),
                              ),
                            ),
                      ),
                  ),
              )
            }

    /** The options of the `fetch` of `call`. */
    private def init(call: HttpCall, abort: dom.AbortSignal): dom.RequestInit =
        val all = js.Dictionary(call.headers.map((k, v) => k -> v.reveal)*)
        new dom.RequestInit:
            method = dom.HttpMethod.POST
            headers = all
            body = call.body
            redirect = dom.RequestRedirect.error
            credentials = dom.RequestCredentials.omit
            referrerPolicy = dom.ReferrerPolicy.`no-referrer`
            cache = dom.RequestCache.`no-store`
            signal = abort
        end new
    end init

    /** The value of `p`; a rejection is a [[HttpFailure.Transport]]. */
    private def await[A](p: => js.Promise[A])(using
        Frame,
    ): A < (Async & Abort[HttpFailure]) =
      Abort.runWith[Throwable](
        Sync.defer(p.toFuture).map(Async.fromFuture(_)),
      ) {
        case Result.Success(a) => a
        case Result.Failure(t) =>
          Abort.fail(HttpFailure.Transport(t.getMessage))
        case Result.Panic(t)   => Abort.fail(HttpFailure.Transport(t.toString))
      }

    /** The bytes of `u`. */
    private def bytes(u: Uint8Array): Chunk[Byte] =
      Chunk.from(
        int8Array2ByteArray(new Int8Array(u.buffer, u.byteOffset, u.length)),
      )

    /** The lines of the body of `r`: each piece waits at most `idle`. */
    private def read(r: dom.Response, idle: Duration)(using
        Frame,
    ): Unit < (Emit[Chunk[String]] & Async & Abort[HttpFailure]) =
      Maybe(r.body) match
          case Absent      => Kyo.unit
          case Present(rs) =>
            val reader = rs.getReader()
            val next   = within(idle, "the body")(await(reader.read()))
            Loop(Sse.Lines.empty) { state =>
              next.map(piece =>
                if piece.done then
                    val last = Sse.Lines.end(state)
                    (if last.isEmpty then Kyo.unit else Emit.value(last))
                      .andThen(Loop.done(()))
                else
                    val (after, ls) = Sse.Lines.feed(state, bytes(piece.value))
                    (if ls.isEmpty then Kyo.unit else Emit.value(ls))
                      .andThen(Loop.continue(after)),
              )
            }

    /** `v`, or a [[HttpFailure.Transport]] when `what` gives no data for
      * `limit`.
      */
    // claude 2026-10-19: created (L4 review)
    private def within[A](limit: Duration, what: String)(
        v: => A < (Async & Abort[HttpFailure]),
    )(using Frame): A < (Async & Abort[HttpFailure]) =
      Async.timeoutWithError(
        limit,
        Result.Failure(
          HttpFailure.Transport(s"$what gave no data for ${limit.show}"),
        ),
      )(v)

    /** The text of the body of an error status: at most [[maxErrorBody]]
      * characters; empty when it cannot be read in `timeout`.
      */
    // claude 2026-10-19: changed: the limit of the wait (L4 review)
    private def errorBody(r: dom.Response, timeout: Duration)(using
        Frame,
    ): String < Async =
      Abort
        .run[HttpFailure](within(timeout, "the error body")(await(r.text())))
        .map(_.fold(_.take(maxErrorBody), _ => "", _ => ""))
end FetchHttp
