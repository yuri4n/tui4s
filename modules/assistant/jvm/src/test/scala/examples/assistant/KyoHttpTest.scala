package examples.assistant

import kyo.*
import kyo.test.*

import java.nio.charset.StandardCharsets.UTF_8

/** The HTTP seam of the JVM ([[KyoHttp]]) against a kyo-http server on the
  * loopback interface (no other network): the lines of a 200 body and the
  * headers that the seam sends, a 429 with `retry-after` and its body, and a
  * refused connection.
  */
// claude 2026-10-19: created (L3)
class KyoHttpTest extends Test[Any]:

    private val stream =
      HttpRoute.postRaw("ok").request(_.bodyText).response(_.bodyStream)

    private val limited =
      HttpRoute.postRaw("limited").request(_.bodyText).response(_.bodyText)

    private def bytes(s: String): Span[Byte] = Span.from(s.getBytes(UTF_8))

    private def server(using Frame) =
      HttpServer.init(0, "127.0.0.1")(
        HttpHandler.init(stream)(req =>
          HttpResponse(HttpStatus.OK).addField(
            "body",
            Stream.init(
              Chunk(
                bytes(
                  s"data: key=${req.headers.get("x-api-key").getOrElse("")}\n",
                ),
                bytes("data: body="),
                bytes(req.fields.body + "\r\n\n中"),
                bytes("文\n"),
              ),
            ),
          ),
        ),
        HttpHandler.init(limited)(_ =>
          HttpResponse(HttpStatus.TooManyRequests)
            .addHeader("retry-after", "7")
            .addField("body", """{"error":{"message":"slow down"}}"""),
        ),
      )

    private val key =
      ApiKey.parse("sk-loopback", KeySource.Environment("X")).get

    private def call(url: String) =
      HttpCall(url, Chunk("x-api-key" -> HeaderValue.Secret("", key)), "{}")

    "a 200 body comes as lines; a 429 fails with its status, retry-after and body; no server is a transport failure" in {
      Abort.run[HttpBindException](server).map {
        case Result.Success(s) =>
          val base = s"http://127.0.0.1:${s.port}"
          for
              ok   <- Abort.run[HttpFailure](
                        KyoHttp.seam().lines(call(s"$base/ok")).run,
                      )
              slow <- Abort.run[HttpFailure](
                        KyoHttp.seam().lines(call(s"$base/limited")).run,
                      )
              none <- Abort.run[HttpFailure](
                        KyoHttp
                          .seam(5.seconds)
                          .lines(call("http://127.0.0.1:1/ok"))
                          .run,
                      )
          yield
              assert(
                ok == Result.succeed(
                  Chunk("data: key=sk-loopback", "data: body={}", "", "中文"),
                ),
                ok.toString,
              )
              assert(
                slow == Result.fail(
                  HttpFailure.Status(
                    429,
                    Present("7"),
                    """{"error":{"message":"slow down"}}""",
                  ),
                ),
                slow.toString,
              )
              assert(
                none match
                    case Result.Failure(HttpFailure.Transport(_)) => true
                    case _                                        => false,
                none.toString,
              )
          end for
        case other             => fail(s"no server: $other")
      }
    }
end KyoHttpTest
