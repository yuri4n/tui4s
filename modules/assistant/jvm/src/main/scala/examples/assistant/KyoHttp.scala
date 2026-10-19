package examples.assistant

import kyo.*

/** The HTTP seam of the JVM ([[HttpSeam]]) on the client of kyo-http: one POST
  * with a text body, the response body as a stream of bytes, cut into lines by
  * [[Sse.Lines]]. A status that is not 2xx fails with [[HttpFailure.Status]]
  * (with its `retry-after` header and its body); a failure of kyo-http (no
  * connection, a timeout, a cut body) fails with [[HttpFailure.Transport]]. The
  * wait for the headers is at most `timeout`; after them, a body that gives no
  * bytes for `idle` fails with [[HttpFailure.Transport]] (a stalled
  * connection). The default `idle` is 5 minutes: a model that thinks before its
  * first text can send no bytes for a long time (Anthropic sends `ping` events;
  * a provider can also send nothing). The seam does not follow a redirect: a
  * 3xx is a [[HttpFailure.Status]], so the key goes only to the host of the
  * call. An interrupt of the reader closes the connection.
  */
// claude 2026-10-19: created (L3); changed: no redirect, the idle limit (L3 review)
object KyoHttp:
    private val route =
      HttpRoute.postRaw("").request(_.bodyText).response(_.bodyStream)

    /** The longest error body that the seam reads. */
    val maxErrorBody: Int = 64 * 1024

    /** The seam of the default client of kyo-http: at most `timeout` for the
      * headers, and at most `idle` between two pieces of the body.
      */
    // claude 2026-10-19: changed: the idle limit of the body (L3 review)
    def seam(
        timeout: Duration = 60.seconds,
        idle: Duration = 5.minutes,
    ): HttpSeam = new HttpSeam:
        def lines(call: HttpCall)(using
            Frame,
        ): Stream[String, Async & Abort[HttpFailure]] =
          Stream {
            Abort.runWith[HttpException](send(call, timeout)) {
              case Result.Success(r) if r.status.isSuccess =>
                read(r.fields.body, idle)
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

    // claude 2026-10-19: changed: no redirect is followed (L3 review)
    private def send(call: HttpCall, timeout: Duration)(using Frame) =
      Abort.get(HttpUrl.parse(call.url)).map { url =>
        val headers = call.headers
          .foldLeft(HttpHeaders.empty)((h, kv) => h.add(kv._1, kv._2.reveal))
        val request = HttpRequest(HttpMethod.POST, url, headers, Record.empty)
          .addField("body", call.body)
        HttpClient.withConfig(_.timeout(timeout).followRedirects(false))(
          HttpClient.use(_.sendWith(route, request)(r => r)),
        )
      }

    /** A piece of the body: its bytes, its end, or a cut (`reason`). */
    // claude 2026-10-19: created (L3 review)
    private enum Piece:
        case Bytes(span: Span[Byte])
        case End
        case Cut(reason: String)

    /** The lines of the bytes of `body`. A fiber reads the body into a channel
      * of one piece; the reader waits at most `idle` for each piece. A failure
      * of the body, or no piece for `idle`, is a cut connection. The end of the
      * reader (also an interrupt) interrupts the fiber and closes the channel.
      */
    // claude 2026-10-19: changed: the idle limit of each piece (L3 review)
    private def read(body: Stream[Span[Byte], Async], idle: Duration)(using
        Frame,
    ): Unit < (Emit[Chunk[String]] & Async & Abort[HttpFailure]) =
      Channel.use[Piece](1) { pieces =>
        val pump: Unit < Async =
          Abort
            .run[Throwable](body.foreach(s => pieces.put(Piece.Bytes(s))))
            .map {
              case Result.Success(_) => Piece.End
              case Result.Failure(t) => Piece.Cut(t.toString)
              case Result.Panic(t)   => Piece.Cut(t.toString)
            }
            .map(last => Abort.run[Closed](pieces.put(last)).unit)
        val next               =
          Async.timeoutWithError(
            idle,
            Result.Failure(
              HttpFailure.Transport(s"the body gave no data for ${idle.show}"),
            ),
          )(
            Abort.recover[Closed](_ => Piece.Cut("the channel closed"))(
              pieces.take,
            ),
          )
        Fiber.use[Nothing, Unit, Any, Any](pump) { _ =>
          Loop(Sse.Lines.empty) { state =>
            next.map {
              case Piece.Bytes(span) =>
                val (after, ls) =
                  Sse.Lines.feed(state, Chunk.from(span.toArray))
                (if ls.isEmpty then Kyo.unit else Emit.value(ls))
                  .andThen(Loop.continue(after))
              case Piece.End         =>
                val last = Sse.Lines.end(state)
                (if last.isEmpty then Kyo.unit else Emit.value(last))
                  .andThen(Loop.done(()))
              case Piece.Cut(reason) =>
                Abort.fail(HttpFailure.Transport(s"the body was cut: $reason"))
            }
          }
        }
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
