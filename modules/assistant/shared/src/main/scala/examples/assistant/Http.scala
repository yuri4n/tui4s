package examples.assistant

import examples.assistant.JsonTree.*
import kyo.*

/** The value of a header: plain text, or a key that only the HTTP interpreter
  * reveals (`prefix` goes before it: "Bearer "). The `toString` of a call never
  * shows a key.
  */
// claude 2026-10-19: created (L3)
enum HeaderValue derives CanEqual:
    /** A text that can show. */
    case Plain(text: String)

    /** A key, after `prefix`. */
    case Secret(prefix: String, key: ApiKey)

    /** The text of the header: only for the request on the wire. */
    def reveal: String = this match
        case Plain(t)     => t
        case Secret(p, k) => p + k.reveal
end HeaderValue

/** One POST of a provider: its URL, its headers and its JSON body. */
// claude 2026-10-19: created (L3)
final case class HttpCall(
    url: String,
    headers: Chunk[(String, HeaderValue)],
    body: String,
)

/** Why a call failed before its body: a status that is not 2xx, or no answer.
  * [[toChatError]] gives its meaning for the app.
  */
// claude 2026-10-19: created (L3)
enum HttpFailure derives CanEqual:
    /** The status `code`, the `retry-after` header and the body. */
    case Status(code: Int, retryAfter: Maybe[String], body: String)

    /** No answer: the connection failed, or the stream was cut. */
    case Transport(reason: String)

    /** The [[ChatError]] of the failure: 401 and 403 are [[ChatError.Auth]];
      * 429 is [[ChatError.RateLimited]] with the delay of `retry-after` (in
      * seconds); 5xx (and 529, overloaded) and no answer are
      * [[ChatError.Network]]; another status is [[ChatError.Protocol]]. The
      * message is the `error.message` of a JSON body, else the start of the
      * body.
      */
    def toChatError: ChatError = this match
        case Status(401 | 403, _, _)                             => ChatError.Auth
        case Status(429, after, _)                               =>
          ChatError.RateLimited(after.flatMap(HttpFailure.seconds))
        case Status(code, _, body) if code >= 500 && code <= 599 =>
          ChatError.Network(s"HTTP $code: ${HttpFailure.message(body)}")
        case Status(code, _, body)                               =>
          ChatError.Protocol(s"HTTP $code: ${HttpFailure.message(body)}")
        case Transport(reason)                                   => ChatError.Network(reason)
end HttpFailure

/** The rules of [[HttpFailure]]. */
// claude 2026-10-19: created (L3)
object HttpFailure:
    /** The delay of a `retry-after` value in seconds ("30", "1.5"); Absent for
      * another form (an HTTP date).
      */
    def seconds(value: String): Maybe[Duration] =
      Maybe.fromOption(
        value.trim.toDoubleOption
          .filter(d => d >= 0 && d < 86400)
          .map(d => (d * 1000).toLong.millis),
      )

    /** The `error.message` of a JSON error body (both providers write one),
      * else the first 200 characters of the body.
      */
    def message(body: String): String =
      JsonTree
        .parse(body)
        .flatMap(_.at("error", "message").flatMap(_.str))
        .getOrElse(body.take(200))
end HttpFailure

/** The HTTP seam of the providers: one POST whose body comes as lines. An
  * interpreter checks the status: a status that is not 2xx fails the stream
  * with [[HttpFailure.Status]] (its body, read in full); a failed connection or
  * a cut body fails it with [[HttpFailure.Transport]]. An interrupt of the
  * reader closes the connection. The JVM interpreter is on the client of
  * kyo-http (`KyoHttp`); a test interpreter gives the lines of a recorded
  * fixture.
  */
// claude 2026-10-19: created (L3)
trait HttpSeam:
    /** The lines of the body of the answer to `call`. */
    def lines(call: HttpCall)(using
        Frame,
    ): Stream[String, Async & Abort[HttpFailure]]
