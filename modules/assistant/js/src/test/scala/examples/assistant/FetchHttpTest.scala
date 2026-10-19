package examples.assistant

import kyo.*
import kyo.test.*
import org.scalajs.dom

import java.nio.charset.StandardCharsets.UTF_8
import scala.scalajs.js as sjs
import scala.scalajs.js.typedarray.{Uint8Array, byteArray2Int8Array}

/** The HTTP seam of the browser ([[FetchHttp]]) on Node.js, with a fake `fetch`
  * that serves the recorded fixtures of L3 (no network): each reply gives the
  * same events as the lines of its fixture; the call has the key, the header of
  * the browser and no redirect; 401 and 429 are typed errors; a cut stream and
  * a body that fails end with a network error; a rejected `fetch` is a network
  * error; an interrupt aborts the request.
  */
// claude 2026-10-19: created (L4)
class FetchHttpTest extends Test[Any]:
    import FetchHttpTest.*

    private val key = ApiKey.parse("sk-browser", KeySource.Browser).get

    private val request =
      ChatRequest(Chunk.empty, Models.anthropic, Absent, "Be brief.")

    /** The events of `wire` on `seam`, or the error of the stream. */
    private def events(wire: Wire, seam: HttpSeam)(using Frame) =
      Abort.run[ChatError](
        SseProvider.make(wire, key, seam).stream(request).run,
      )

    "a reply over fetch gives the events of the lines of its fixture (both wires, 7 bytes at a time)" in {
      val cases = Chunk(
        (AssistantWeb.anthropic, "anthropic-happy.sse"),
        (OpenAI, "openai-happy.sse"),
        (AssistantWeb.anthropic, "anthropic-cut.sse"),
        (OpenAI, "openai-error.sse"),
      )
      Kyo
        .foreach(cases)((wire, name) =>
          for
              got  <- events(wire, FetchHttp.seam(serve(200, pieces(name))))
              want <- events(wire, linesOf(name))
          yield (name, got, want),
        )
        .map(rs =>
          assert(
            rs.forall((_, got, want) => got == want && got.isSuccess) &&
              (rs.head._2 match
                  case Result.Success(es) =>
                    es.lastOption.exists {
                      case _: ChatEvent.Stop => true
                      case _                 => false
                    }
                  case _                  => false),
            rs.toString,
          ),
        )
    }

    "the call: the key, the version and the header of the browser; no redirect, no cookie, no referrer" in {
      for
          seen <- Sync.defer(sjs.Array[(String, dom.RequestInit)]())
          _    <- events(
                    AssistantWeb.anthropic,
                    FetchHttp.seam(
                      recording(seen, serve(200, pieces("anthropic-happy.sse"))),
                    ),
                  )
      yield
          val (url, init) = seen.head
          val headers     = init.headers.get.asInstanceOf[sjs.Dictionary[String]]
          assert(
            url == Anthropic.url &&
              headers.get("x-api-key").contains("sk-browser") &&
              headers.get("anthropic-version").contains(Anthropic.version) &&
              headers
                .get("anthropic-dangerous-direct-browser-access")
                .contains("true") &&
              init.redirect.contains(dom.RequestRedirect.error) &&
              init.credentials.contains(dom.RequestCredentials.omit) &&
              init.referrerPolicy.contains(dom.ReferrerPolicy.`no-referrer`),
            headers.toString,
          )
      end for
    }

    "401 is an Auth error; 429 is a rate limit with the delay of retry-after" in {
      for
          auth  <-
            events(
              Anthropic,
              FetchHttp.seam(serve(401, Chunk(fixture("anthropic-401.json")))),
            )
          limit <- events(
                     Anthropic,
                     FetchHttp.seam(
                       serve(
                         429,
                         Chunk(fixture("anthropic-429.json")),
                         Map("retry-after" -> "7"),
                       ),
                     ),
                   )
      yield assert(
        auth == Result.Failure(ChatError.Auth) &&
          limit == Result.Failure(ChatError.RateLimited(Present(7.seconds))),
        s"$auth $limit",
      )
    }

    "a body that fails, and a fetch that is rejected, are network errors" in {
      for
          cut     <- events(Anthropic, FetchHttp.seam(failing("data: {}\n")))
          offline <- events(
                       Anthropic,
                       FetchHttp.seam((_, _) =>
                         sjs.Promise.reject(sjs.JavaScriptException("offline")),
                       ),
                     )
      yield assert(
        isNetwork(cut) && isNetwork(offline),
        s"$cut $offline",
      )
    }

    // claude 2026-10-19: created (L4 review)
    "a fetch that gives no headers within the timeout is a network error, and its request is aborted" in {
      val seen = sjs.Array[(String, dom.RequestInit)]()
      for
          r       <- events(
                       Anthropic,
                       FetchHttp.seam(
                         recording(
                           seen,
                           (_, _) => new sjs.Promise[dom.Response]((_, _) => ()),
                         ),
                         timeout = 50.millis,
                       ),
                     )
          aborted <- until(seen.head._2.signal.exists(_.aborted))
      yield assert(isNetwork(r) && aborted, r.toString)
      end for
    }

    "an interrupt of the reader aborts the request" in {
      val seen = sjs.Array[(String, dom.RequestInit)]()
      for
          fiber   <- Fiber.initUnscoped(
                       events(Anthropic, FetchHttp.seam(recording(seen, endless))),
                     )
          _       <- until(seen.nonEmpty)
          _       <- fiber.interrupt
          aborted <- until(seen.head._2.signal.exists(_.aborted))
      yield assert(aborted)
      end for
    }
end FetchHttpTest

/** The fake `fetch` of the tests: the fixtures of L3 as bodies of a `Response`
  * of Node.sjs.
  */
// claude 2026-10-19: created (L4)
object FetchHttpTest:
    /** The text of the fixture `name` (generated from the fixtures of the JVM
      * tests: `Fixtures`).
      */
    def fixture(name: String): String = Fixtures.texts(name)

    /** `b` as an `Uint8Array`. */
    def raw(b: Array[Byte]): Uint8Array =
        val i8 = byteArray2Int8Array(b)
        new Uint8Array(i8.buffer, i8.byteOffset, i8.length)

    /** The bytes of `text` as an `Uint8Array`. */
    def bytes(text: String): Uint8Array = raw(text.getBytes(UTF_8))

    /** The bytes of the fixture `name`, 7 at a time (a character of more than
      * one byte can be cut).
      */
    def pieces(name: String): Chunk[Uint8Array] =
      Chunk.from(fixture(name).getBytes(UTF_8).grouped(7).toSeq).map(raw)

    /** A body that gives `chunks`, then `last` on its controller. */
    private def body(chunks: Chunk[Uint8Array])(last: sjs.Dynamic => Any) =
      sjs.Dynamic.newInstance(sjs.Dynamic.global.ReadableStream)(
        sjs.Dynamic.literal(start = ((c: sjs.Dynamic) =>
            chunks.foreach(u => c.enqueue(u))
            val _ = last(c),
        ): sjs.Function1[sjs.Dynamic, Unit]),
      )

    /** A response of `status` with `headers` and a body. */
    private def response(
        status: Int,
        b: sjs.Any,
        headers: Map[String, String],
    ) =
      sjs.Dynamic
        .newInstance(sjs.Dynamic.global.Response)(
          b,
          sjs.Dynamic
            .literal(status = status, headers = sjs.Dictionary(headers.toSeq*)),
        )
        .asInstanceOf[dom.Response]

    /** A fetch that answers `status` with the body `chunks` (raw bytes, not cut
      * again: a JSON error body is one chunk).
      */
    def serve(
        status: Int,
        chunks: Chunk[Uint8Array] | Chunk[String],
        headers: Map[String, String] = Map.empty,
    ): FetchHttp.Fetch = (_, _) =>
        val bs = chunks.map {
          case u: Uint8Array => u
          case t: String     => bytes(t)
        }
        sjs.Promise.resolve(
          response(status, body(bs)(_.close()), headers),
        )

    /** A fetch that gives `text`, then fails its body. */
    def failing(text: String): FetchHttp.Fetch = (_, _) =>
      sjs.Promise.resolve(
        response(
          200,
          body(Chunk(bytes(text)))(_.error(sjs.Error("reset"))),
          Map.empty,
        ),
      )

    /** A fetch whose body gives one line and never ends. */
    val endless: FetchHttp.Fetch = (_, _) =>
      sjs.Promise.resolve(
        response(200, body(Chunk(bytes(": open\n")))(_ => ()), Map.empty),
      )

    /** `f`, and each call into `seen`. */
    def recording(
        seen: sjs.Array[(String, dom.RequestInit)],
        f: FetchHttp.Fetch,
    ): FetchHttp.Fetch = (url, init) =>
        val _ = seen.push((url, init))
        f(url, init)

    /** A seam that gives the lines of the fixture `name` in one piece: the
      * oracle of the fetch seam.
      */
    def linesOf(name: String): HttpSeam = new HttpSeam:
        def lines(call: HttpCall)(using
            Frame,
        ): Stream[String, Async & Abort[HttpFailure]] =
            val (st, ls) =
              Sse.Lines.feed(
                Sse.Lines.empty,
                Chunk.from(fixture(name).getBytes(UTF_8)),
              )
            Stream.init(ls ++ Sse.Lines.end(st))
        end lines

    /** True when `r` is a network error. */
    def isNetwork(r: Result[ChatError, Chunk[ChatEvent]]): Boolean = r match
        case Result.Failure(ChatError.Network(_)) => true
        case _                                    => false

    /** Waits until `p` holds (at most 5 s); true when it did. */
    def until(p: => Boolean)(using Frame): Boolean < Async =
      Abort
        .run[Timeout](
          Async.timeout(5.seconds)(
            Loop(()) { _ =>
              if p then Loop.done(true)
              else Async.sleep(5.millis).andThen(Loop.continue(()))
            },
          ),
        )
        .map(_.getOrElse(false))
end FetchHttpTest
