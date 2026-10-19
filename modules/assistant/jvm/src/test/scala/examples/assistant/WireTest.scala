package examples.assistant

import kyo.*
import kyo.test.*

import java.nio.charset.StandardCharsets.UTF_8

/** The two providers on recorded fixtures (`src/test/resources/fixtures`): a
  * test seam gives the bytes of a recorded stream in chunks of 7 bytes through
  * [[Sse.Lines]], or fails as a recorded status does. No network.
  */
// claude 2026-10-19: created (L3)
class WireTest extends Test[Any]:
    import WireTest.*

    private val key =
      ApiKey.parse("sk-test-SECRET", KeySource.Environment("X")).get

    private val request = ChatRequest(
      Chunk(Message(Role.User, "hi", Instant.Epoch, MessageStatus.Done)),
      "a-model",
      Absent,
      "Be brief.",
    )

    private def run(wire: Wire, seam: HttpSeam)(using Frame) =
      Abort.run[ChatError](
        SseProvider.make(wire, key, seam).stream(request).run,
      )

    private def text(es: Chunk[ChatEvent]): String =
      es.collect { case ChatEvent.TextDelta(t) => t }.mkString

    "Anthropic: a reply gives its text, then the stop with the usage" in {
      run(Anthropic, fixture("anthropic-happy.sse")).map {
        case Result.Success(es) =>
          assert(
            text(es) == "Hello! How can I help? 中文 👍\nA second line." &&
              es.last == ChatEvent.Stop(StopReason.EndTurn, Usage(21, 12)),
          )
        case other              => fail(other.toString)
      }
    }

    "OpenAI: a reply gives its text, then the stop with the usage" in {
      run(OpenAI, fixture("openai-happy.sse")).map {
        case Result.Success(es) =>
          assert(
            text(es) == "Hello! How can I help? 中文 👍\nA second line." &&
              es.last == ChatEvent.Stop(StopReason.EndTurn, Usage(21, 12)),
          )
        case other              => fail(other.toString)
      }
    }

    "an error in the stream ends the reply with an error after the partial text" in {
      for
          a <- run(Anthropic, fixture("anthropic-error.sse"))
          o <- run(OpenAI, fixture("openai-error.sse"))
      yield
          def check(r: Result[ChatError, Chunk[ChatEvent]]) = r match
              case Result.Success(es) =>
                text(es) == "Partial answer" && (es.last match
                    case ChatEvent.Error(ChatError.Network(_)) => true
                    case _                                     => false)
              case _                  => false
          assert(check(a) && check(o))
    }

    "a cut connection (no stop) ends the reply with a network error" in {
      for
          a <- run(Anthropic, fixture("anthropic-cut.sse"))
          o <- run(OpenAI, fixture("openai-cut.sse"))
      yield assert(
        a == Result.succeed(
          Chunk(
            ChatEvent.TextDelta("The connection was cut he"),
            ChatEvent.Error(
              ChatError.Network("the stream ended before message_stop"),
            ),
          ),
        ) && o == Result.succeed(
          Chunk(
            ChatEvent.TextDelta("The connection was cut he"),
            ChatEvent.Error(ChatError.Network("the stream ended before [DONE]")),
          ),
        ),
      )
    }

    "a 401 is an Auth error" in {
      for
          a <- run(Anthropic, status(401, Absent, "anthropic-401.json"))
          o <- run(OpenAI, status(401, Absent, "openai-401.json"))
      yield assert(
        a == Result.fail(ChatError.Auth) && o == Result.fail(ChatError.Auth),
      )
    }

    "a 429 is a rate limit with the delay of retry-after" in {
      for
          a <- run(Anthropic, status(429, Present("7"), "anthropic-429.json"))
          n <- run(Anthropic, status(429, Absent, "anthropic-429.json"))
      yield assert(
        a == Result.fail(ChatError.RateLimited(Present(7.seconds))) &&
          n == Result.fail(ChatError.RateLimited(Absent)),
      )
    }

    "a 5xx is a network error, another status a protocol error, with the message of the body" in {
      val body =
        """{"type":"error","error":{"type":"invalid_request_error","message":"temperature: not supported"}}"""
      for
          s <- run(Anthropic, failing(HttpFailure.Status(529, Absent, body)))
          p <- run(Anthropic, failing(HttpFailure.Status(400, Absent, body)))
          t <- run(OpenAI, failing(HttpFailure.Transport("connection refused")))
      yield assert(
        s == Result.fail(
          ChatError.Network("HTTP 529: temperature: not supported"),
        ) &&
          p == Result.fail(
            ChatError.Protocol("HTTP 400: temperature: not supported"),
          ) &&
          t == Result.fail(ChatError.Network("connection refused")),
      )
      end for
    }

    "Anthropic: the call has the key, the version and a JSON body; it never shows the key" in {
      val call = Anthropic.call(request, key)
      val body = JsonTree.parse(call.body).get
      import JsonTree.*
      assert(
        call.url == Anthropic.url &&
          call.headers.toMap.get("x-api-key").map(_.reveal) == Some(
            "sk-test-SECRET",
          ) &&
          call.headers.toMap.get("anthropic-version").map(_.reveal) == Some(
            "2023-06-01",
          ) &&
          call.headers.toMap.get("content-type").map(_.reveal) == Some(
            "application/json",
          ) &&
          body.field("model").flatMap(_.str) == Present("a-model") &&
          body.field("system").flatMap(_.str) == Present("Be brief.") &&
          body.field("temperature") == Absent &&
          body
            .at("messages")
            .flatMap(_.index(0))
            .flatMap(_.field("content"))
            .flatMap(_.str) ==
          Present("hi") &&
          !call.toString.contains("SECRET"),
      )
    }

    "OpenAI: the call has the bearer key, the system message and the temperature" in {
      val call = OpenAI.call(request.copy(temperature = Present(0.5)), key)
      val body = JsonTree.parse(call.body).get
      import JsonTree.*
      assert(
        call.url == OpenAI.url &&
          call.headers.toMap.get("authorization").map(_.reveal) ==
          Some("Bearer sk-test-SECRET") &&
          body
            .at("messages")
            .flatMap(_.index(0))
            .flatMap(_.field("role"))
            .flatMap(_.str) ==
          Present("system") &&
          body.at("stream_options", "include_usage") == Present(
            Structure.Value.Bool(true),
          ) &&
          body.field("temperature") == Present(Structure.Value.Decimal(0.5)) &&
          !call.toString.contains("SECRET"),
      )
    }
end WireTest

/** The test seams of the fixtures. */
// claude 2026-10-19: created (L3)
object WireTest:
    /** The text of the fixture `name`. */
    def resource(name: String): String =
        val in = getClass.getResourceAsStream(s"/fixtures/$name").nn
        try String(in.readAllBytes().nn, UTF_8)
        finally in.close()

    /** A seam that gives the bytes of the fixture `name`, 7 bytes at a time, as
      * lines.
      */
    def fixture(name: String): HttpSeam = new HttpSeam:
        def lines(call: HttpCall)(using
            Frame,
        ): Stream[String, Async & Abort[HttpFailure]] =
            val bytes      = Chunk.from(resource(name).getBytes(UTF_8))
            val chunks     =
              Chunk.from(bytes.toSeq.grouped(7).map(Chunk.from).toSeq)
            val (rest, ls) =
              chunks.foldLeft((Sse.Lines.empty, Chunk.empty[String])) {
                case ((s, acc), c) =>
                  val (s2, l) = Sse.Lines.feed(s, c)
                  (s2, acc ++ l)
              }
            Stream.init(ls ++ Sse.Lines.end(rest))
        end lines

    /** A seam that fails with `failure`. */
    def failing(failure: HttpFailure): HttpSeam = new HttpSeam:
        def lines(call: HttpCall)(using
            Frame,
        ): Stream[String, Async & Abort[HttpFailure]] =
          Stream(Abort.fail(failure))

    /** A seam that answers `code` with the body of the fixture `name`. */
    def status(code: Int, retryAfter: Maybe[String], name: String): HttpSeam =
      failing(HttpFailure.Status(code, retryAfter, resource(name)))
end WireTest
