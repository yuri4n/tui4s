package examples.assistant

import examples.assistant.JsonTree.*
import kyo.*

/** The wire of the Messages API of Anthropic, with `stream: true`. The request
  * has the headers `x-api-key`, `anthropic-version` and `content-type`; the
  * model, `max_tokens`, the system prompt (when it is not empty), the
  * temperature (only when the settings give one: the latest Claude models do
  * not take sampling parameters, and the API then answers HTTP 400) and the
  * messages.
  *
  * A request to the default model ([[Models.anthropic]]) also has the server
  * fallbacks: the header `anthropic-beta: server-side-fallback-2026-07-01` and
  * `"fallbacks": "default"`. When the safety rules of that model decline a
  * request, the API runs it again on the fallback model that Anthropic
  * recommends for that category, in the same stream (a `fallback` content block
  * marks the change; the wire ignores it, and the text of the two models is
  * joined). A request to another model has no fallbacks: that model can refuse
  * the parameter. A refusal that stays is the stop reason `refusal`
  * ([[StopReason.Refusal]]): the transcript shows a note.
  *
  * The events: `message_start` gives the input tokens; `content_block_delta`
  * with a `text_delta` gives a piece of the text (a thinking block, which the
  * latest models stream with no text by default, gives nothing);
  * `message_delta` gives the stop reason and the output tokens; `message_stop`
  * gives the stop; `error` gives an error (`overloaded_error` and `api_error`
  * are network errors, `rate_limit_error` a rate limit, `authentication_error`
  * and `permission_error` a key error, another one a protocol error).
  */
// claude 2026-10-19: created (L3); changed: the fallbacks of the default model (L3 review)
object Anthropic extends Wire:
    /** The endpoint of the Messages API. */
    val url: String = "https://api.anthropic.com/v1/messages"

    /** The version of the API (the header `anthropic-version`). */
    val version: String = "2023-06-01"

    /** The beta of the server fallbacks (the header `anthropic-beta`). */
    // claude 2026-10-19: created (L3 review)
    val fallbackBeta: String = "server-side-fallback-2026-07-01"

    /** True when a request to `model` has the server fallbacks: only the
      * default model.
      */
    // claude 2026-10-19: created (L3 review)
    def fallbacks(model: String): Boolean = model == Models.anthropic

    /** The limit of the tokens of a reply (a stream can be long). */
    val maxTokens: Int = 64000

    /** Where the fold is: open (the input tokens, the output tokens and the
      * stop reason so far), or closed (a stop or an error came).
      */
    enum State derives CanEqual:
        case Open(usage: Usage, stop: Maybe[StopReason])
        case Closed

    def name: String = "Anthropic"

    // claude 2026-10-19: changed: the beta header of the fallbacks (L3 review)
    def call(request: ChatRequest, key: ApiKey)(using Frame): HttpCall =
      HttpCall(
        url,
        Chunk(
          "x-api-key"         -> HeaderValue.Secret("", key),
          "anthropic-version" -> HeaderValue.Plain(version),
          "content-type"      -> HeaderValue.Plain("application/json"),
          "accept"            -> HeaderValue.Plain("text/event-stream"),
        ) ++ (if fallbacks(request.model) then
                  Chunk("anthropic-beta" -> HeaderValue.Plain(fallbackBeta))
              else Chunk.empty),
        JsonTree.render(body(request)),
      )

    /** The JSON body of `request`. */
    // claude 2026-10-19: changed: the fallbacks of the default model (L3 review)
    def body(request: ChatRequest): Value =
      obj(
        "model"       -> text(request.model),
        "max_tokens"  -> integer(maxTokens.toLong),
        "stream"      -> boolean(true),
        "fallbacks"   ->
          (if fallbacks(request.model) then text("default") else Absent),
        "system"      ->
          (if request.system.trim.isEmpty then Absent
           else text(request.system)),
        "temperature" -> request.temperature.flatMap(number),
        "messages"    -> Present(
          arr(
            request.messages.map(m =>
              obj(
                "role"    -> text(m.role match
                    case Role.User      => "user"
                    case Role.Assistant => "assistant"),
                "content" -> text(m.content),
              ),
            ),
          ),
        ),
      )

    def start: State = State.Open(Usage.zero, Absent)

    def step(state: State, event: SseEvent)(using
        Frame,
    ): (State, Chunk[ChatEvent]) =
        state match
            case State.Closed            => (state, Chunk.empty)
            case State.Open(usage, stop) =>
              parse(event.data) match
                  case Absent     =>
                    if event.event == "ping" then (state, Chunk.empty)
                    else
                        fail(
                          ChatError.Protocol(
                            s"an event that is not JSON: ${event.data.take(80)}",
                          ),
                        )
                  case Present(v) =>
                    v.field("type").flatMap(_.str).getOrElse(event.event) match
                        case "message_start"       =>
                          val in = v
                            .at("message", "usage", "input_tokens")
                            .flatMap(_.int)
                          (
                            State.Open(
                              usage.copy(input = in.getOrElse(usage.input)),
                              stop,
                            ),
                            Chunk.empty,
                          )
                        case "content_block_delta" =>
                          val text = v
                            .field("delta")
                            .filter(d =>
                              d.field("type")
                                .flatMap(_.str)
                                .contains("text_delta"),
                            )
                            .flatMap(_.field("text"))
                            .flatMap(_.str)
                          (
                            state,
                            text.fold(Chunk.empty)(t =>
                              Chunk(ChatEvent.TextDelta(t)),
                            ),
                          )
                        case "message_delta"       =>
                          val reason = v
                            .at("delta", "stop_reason")
                            .flatMap(_.str)
                            .map(StopReason.of)
                          val out    =
                            v.at("usage", "output_tokens").flatMap(_.int)
                          (
                            State.Open(
                              usage.copy(output = out.getOrElse(usage.output)),
                              reason.orElse(stop),
                            ),
                            Chunk.empty,
                          )
                        case "message_stop"        =>
                          (
                            State.Closed,
                            Chunk(
                              ChatEvent.Stop(
                                stop.getOrElse(StopReason.EndTurn),
                                usage,
                              ),
                            ),
                          )
                        case "error"               =>
                          fail(
                            error(
                              v.at("error", "type")
                                .flatMap(_.str)
                                .getOrElse(""),
                              v.at("error", "message")
                                .flatMap(_.str)
                                .getOrElse(event.data.take(200)),
                            ),
                          )
                        case _                     => (state, Chunk.empty)
        end match
    end step

    def end(state: State): Chunk[ChatEvent] = state match
        case State.Closed     => Chunk.empty
        case State.Open(_, _) =>
          Chunk(
            ChatEvent.Error(
              ChatError.Network("the stream ended before message_stop"),
            ),
          )

    /** The [[ChatError]] of an `error` event of the type `kind`. */
    def error(kind: String, message: String): ChatError = kind match
        case "overloaded_error" | "api_error"            =>
          ChatError.Network(s"$kind: $message")
        case "rate_limit_error"                          => ChatError.RateLimited(Absent)
        case "authentication_error" | "permission_error" => ChatError.Auth
        case other                                       => ChatError.Protocol(s"$other: $message")

    private def fail(e: ChatError): (State, Chunk[ChatEvent]) =
      (State.Closed, Chunk(ChatEvent.Error(e)))
end Anthropic

/** The wire of the Chat Completions API of OpenAI, with `stream: true` and
  * `stream_options.include_usage`. The request has the header
  * `Authorization: Bearer <key>`; the model, the system prompt as the first
  * message (when it is not empty), the temperature (only when the settings give
  * one) and the messages.
  *
  * The events are `data:` lines: a chunk gives `choices[0].delta.content` (a
  * piece of the text) and `choices[0].finish_reason`; the last chunk gives the
  * `usage`; `data: [DONE]` gives the stop; a chunk with an `error` gives an
  * error (`rate_limit_exceeded` a rate limit, `invalid_api_key` a key error,
  * `server_error` a network error, another one a protocol error).
  */
// claude 2026-10-19: created (L3)
object OpenAI extends Wire:
    /** The endpoint of the Chat Completions API. */
    val url: String = "https://api.openai.com/v1/chat/completions"

    /** Where the fold is: open (the usage and the finish reason so far), or
      * closed.
      */
    enum State derives CanEqual:
        case Open(usage: Usage, finish: Maybe[StopReason])
        case Closed

    def name: String = "OpenAI"

    def call(request: ChatRequest, key: ApiKey)(using Frame): HttpCall =
      HttpCall(
        url,
        Chunk(
          "authorization" -> HeaderValue.Secret("Bearer ", key),
          "content-type"  -> HeaderValue.Plain("application/json"),
          "accept"        -> HeaderValue.Plain("text/event-stream"),
        ),
        JsonTree.render(body(request)),
      )

    /** The JSON body of `request`. */
    def body(request: ChatRequest): Value =
        val system          =
          if request.system.trim.isEmpty then Chunk.empty
          else
              Chunk(
                obj("role" -> text("system"), "content" -> text(request.system)),
              )
        obj(
          "model"          -> text(request.model),
          "stream"         -> boolean(true),
          "stream_options" -> Present(obj("include_usage" -> boolean(true))),
          "temperature"    -> request.temperature.flatMap(number),
          "messages"       -> Present(
            arr(
              system ++ request.messages.map(m =>
                obj(
                  "role"    -> text(m.role match
                      case Role.User      => "user"
                      case Role.Assistant => "assistant"),
                  "content" -> text(m.content),
                ),
              ),
            ),
          ),
        )
    end body

    def start: State = State.Open(Usage.zero, Absent)

    def step(state: State, event: SseEvent)(using
        Frame,
    ): (State, Chunk[ChatEvent]) =
        state match
            case State.Closed              => (state, Chunk.empty)
            case State.Open(usage, finish) =>
              if event.data.trim == "[DONE]" then
                  (
                    State.Closed,
                    Chunk(
                      ChatEvent.Stop(
                        finish.getOrElse(StopReason.EndTurn),
                        usage,
                      ),
                    ),
                  )
              else
                  parse(event.data) match
                      case Absent     =>
                        fail(
                          ChatError.Protocol(
                            s"a chunk that is not JSON: ${event.data.take(80)}",
                          ),
                        )
                      case Present(v) =>
                        v.field("error") match
                            case Present(e) =>
                              fail(
                                error(
                                  e.field("code")
                                    .flatMap(_.str)
                                    .orElse(e.field("type").flatMap(_.str))
                                    .getOrElse(""),
                                  e.field("message")
                                    .flatMap(_.str)
                                    .getOrElse(""),
                                ),
                              )
                            case Absent     =>
                              val choice =
                                v.field("choices").flatMap(_.index(0))
                              val text   = choice
                                .flatMap(_.at("delta", "content"))
                                .flatMap(_.str)
                              val reason = choice
                                .flatMap(_.field("finish_reason"))
                                .flatMap(_.str)
                                .map(StopReason.of)
                              // "usage": null in each chunk before the last
                              val used   = v
                                .field("usage")
                                .flatMap(u =>
                                  u.field("prompt_tokens")
                                    .flatMap(_.int)
                                    .map(p =>
                                      Usage(
                                        p,
                                        u.field("completion_tokens")
                                          .flatMap(_.int)
                                          .getOrElse(0),
                                      ),
                                    ),
                                )
                              (
                                State.Open(
                                  used.getOrElse(usage),
                                  reason.orElse(finish),
                                ),
                                text
                                  .filter(_.nonEmpty)
                                  .fold(Chunk.empty)(t =>
                                    Chunk(ChatEvent.TextDelta(t)),
                                  ),
                              )
              end if
        end match
    end step

    def end(state: State): Chunk[ChatEvent] = state match
        case State.Closed     => Chunk.empty
        case State.Open(_, _) =>
          Chunk(
            ChatEvent.Error(ChatError.Network("the stream ended before [DONE]")),
          )

    /** The [[ChatError]] of an error chunk with the code `code`. */
    def error(code: String, message: String): ChatError = code match
        case "rate_limit_exceeded"                  => ChatError.RateLimited(Absent)
        case "invalid_api_key"                      => ChatError.Auth
        case "server_error" | "service_unavailable" =>
          ChatError.Network(s"$code: $message")
        case other                                  => ChatError.Protocol(s"$other: $message")

    private def fail(e: ChatError): (State, Chunk[ChatEvent]) =
      (State.Closed, Chunk(ChatEvent.Error(e)))
end OpenAI
