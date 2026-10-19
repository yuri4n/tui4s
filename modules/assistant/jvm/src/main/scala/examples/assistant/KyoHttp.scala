package examples.assistant

import kyo.*

/** The HTTP seam of the JVM ([[HttpSeam]]) on the client of kyo-http: one POST
  * with a text body, the response body as a stream of bytes, cut into lines by
  * [[Sse.Lines]]. A status that is not 2xx fails with [[HttpFailure.Status]]
  * (with its `retry-after` header and its body); a failure of kyo-http (no
  * connection, a timeout, a cut body) fails with [[HttpFailure.Transport]]. The
  * wait for the headers is at most `timeout`; the body has no limit (Esc stops
  * a reply). An interrupt of the reader closes the connection.
  */
// claude 2026-10-19: created (L3)
object KyoHttp:
    private val route =
      HttpRoute.postRaw("").request(_.bodyText).response(_.bodyStream)

    /** The longest error body that the seam reads. */
    val maxErrorBody: Int = 64 * 1024

    /** The seam of the default client of kyo-http. */
    def seam(timeout: Duration = 60.seconds): HttpSeam = new HttpSeam:
        def lines(call: HttpCall)(using
            Frame,
        ): Stream[String, Async & Abort[HttpFailure]] =
          Stream {
            Abort.runWith[HttpException](send(call, timeout)) {
              case Result.Success(r) if r.status.isSuccess =>
                read(r.fields.body)
              case Result.Success(r)                       =>
                errorBody(r).map(body =>
                  Abort.fail(
                    HttpFailure.Status(
                      r.status.code,
                      r.headers.get("retry-after"),
                      body,
                    ),
                  ),
                )
              case Result.Failure(e)                       =>
                Abort.fail(HttpFailure.Transport(e.getMessage))
              case Result.Panic(t)                         =>
                Abort.fail(HttpFailure.Transport(t.toString))
            }
          }

    private def send(call: HttpCall, timeout: Duration)(using Frame) =
      Abort.get(HttpUrl.parse(call.url)).map { url =>
        val headers = call.headers
          .foldLeft(HttpHeaders.empty)((h, kv) => h.add(kv._1, kv._2.reveal))
        val request = HttpRequest(HttpMethod.POST, url, headers, Record.empty)
          .addField("body", call.body)
        HttpClient.withConfig(_.timeout(timeout))(
          HttpClient.use(_.sendWith(route, request)(r => r)),
        )
      }

    /** The lines of the bytes of `body`; a failure of the stream is a cut
      * connection.
      */
    private def read(body: Stream[Span[Byte], Async])(using Frame) =
      Abort.runWith[Throwable](
        body.fold(Sse.Lines.empty) { (state, span) =>
          val (next, ls) = Sse.Lines.feed(state, Chunk.from(span.toArray))
          (if ls.isEmpty then Kyo.unit else Emit.value(ls)).andThen(next)
        },
      ) {
        case Result.Success(state) =>
          val last = Sse.Lines.end(state)
          if last.isEmpty then Kyo.unit else Emit.value(last)
        case Result.Failure(t)     =>
          Abort.fail(HttpFailure.Transport(s"the body was cut: $t"))
        case Result.Panic(t)       =>
          Abort.fail(HttpFailure.Transport(s"the body was cut: $t"))
      }

    /** The text of the body of an error status: at most [[maxErrorBody]] bytes. */
    private def errorBody(r: HttpResponse["body" ~ Stream[Span[Byte], Async]])(
        using Frame,
    ): String < Async =
      r.rawBody match
          case Present(b) => b
          case Absent     =>
            Abort
              .run[Throwable](
                r.fields.body
                  .fold(Chunk.empty[Byte])((acc, s) =>
                    (acc ++ Chunk.from(s.toArray)).take(maxErrorBody),
                  ),
              )
              .map(
                _.fold(
                  bytes =>
                    String(
                      bytes.toArray,
                      java.nio.charset.StandardCharsets.UTF_8,
                    ),
                  _ => "",
                  _ => "",
                ),
              )
end KyoHttp
