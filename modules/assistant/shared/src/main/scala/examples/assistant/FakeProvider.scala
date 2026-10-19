package examples.assistant

import kyo.*

/** A scripted chat provider: no network and no key. Its script gives the reply
  * to a request: the pieces of the text and how the reply ends (a stop, or a
  * failure). It streams the pieces with a pause of `delay` before each one
  * (Kyo's `Clock`: a test under `Clock.withTimeControl` moves the time on). The
  * app takes it with the flag `--fake` or when it finds no key; the tests take
  * it for every scenario.
  *
  * {{{
  * val provider = FakeProvider.make(FakeProvider.demo, delay = 30.millis)
  * }}}
  */
// claude 2026-10-19: created (L3)
object FakeProvider:
    /** How a scripted reply ends. */
    enum Ending derives CanEqual:
        /** A stop with `reason`. */
        case Stop(reason: StopReason)

        /** A failure of the stream with `error` (`Abort[ChatError]`). */
        case Fail(error: ChatError)

    /** A scripted reply: its pieces of text, then its end. */
    final case class Reply(pieces: Chunk[String], ending: Ending)
        derives CanEqual

    /** The script: the reply to a request. */
    type Script = ChatRequest => Reply

    /** The text of the last message of the person in `r`. */
    def lastUserText(r: ChatRequest): String =
      r.messages.reverse.find(_.role == Role.User).fold("")(_.content)

    /** The pieces of `text`: its words, each with the spaces before it. */
    def pieces(text: String): Chunk[String] =
      Chunk.from("""\s*\S+""".r.findAllIn(text).toSeq)

    /** The script of the demo: the message "/fail" gives a reply that fails
      * half way with a network error; another message gives a short answer that
      * names the message.
      */
    val demo: Script = r =>
        val said = lastUserText(r).trim
        if said == "/fail" then
            Reply(
              pieces("This reply fails half way, as you asked."),
              Ending.Fail(
                ChatError.Network("the fake provider failed on purpose"),
              ),
            )
        else
            Reply(
              pieces(
                s"""You said: "${Title
                    .of(said, 40)}". I am the fake provider """ +
                  "of tui4s: I answer from a script, with no network and no " +
                  "key. Send /fail to see an error, or press Esc while I " +
                  "write to stop me.",
              ),
              Ending.Stop(StopReason.EndTurn),
            )
        end if

    /** The provider of `script`, with a pause of `delay` before each piece. The
      * usage of a reply counts one input token for 4 characters of the request,
      * and one output token for each piece.
      */
    def make(script: Script = demo, delay: Duration = 30.millis): ChatProvider =
      new ChatProvider:
          def stream(request: ChatRequest)(using
              Frame,
          ): Stream[ChatEvent, Async & Abort[ChatError]] =
              val reply = script(request)
              val input =
                (request.system.length + request.messages
                  .map(_.content.length)
                  .sum) / 4
              Stream {
                Kyo
                  .foreachDiscard(reply.pieces)(p =>
                    Async
                      .sleep(delay)
                      .andThen(Emit.value(Chunk(ChatEvent.TextDelta(p)))),
                  )
                  .andThen(reply.ending match
                      case Ending.Stop(reason) =>
                        Emit.value(
                          Chunk(
                            ChatEvent
                              .Stop(reason, Usage(input, reply.pieces.size)),
                          ),
                        )
                      case Ending.Fail(e)      => Abort.fail(e))
              }
          end stream
end FakeProvider
