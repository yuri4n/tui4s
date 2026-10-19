package examples.assistant

import kyo.*

/** A JSON value as a tree: Kyo's `Structure.Value` with Kyo's `Json` (kyo-ui
  * brings kyo-schema). The providers build their request bodies with it and
  * read the fields of their events with it, so the app writes no JSON parser
  * and no codec for the wire.
  */
// claude 2026-10-19: created (L3)
object JsonTree:
    /** A JSON value. */
    type Value = Structure.Value

    /** The value of the JSON text `text`; Absent when it is not JSON. */
    def parse(text: String)(using Frame): Maybe[Value] =
      Json.decode[Value](text) match
          case Result.Success(v) => Present(v)
          case _                 => Absent

    /** The JSON text of `v`. */
    def render(v: Value)(using Frame): String = Json.encode(v)

    /** An object of the fields that are present, in order. */
    def obj(fields: (String, Maybe[Value])*): Value =
      Structure.Value.Record(Chunk.from(fields).collect {
        case (k, Present(v)) => (k, v)
      })

    /** An array. */
    def arr(values: Chunk[Value]): Value = Structure.Value.Sequence(values)

    /** A string. */
    def text(s: String): Maybe[Value] = Present(Structure.Value.Str(s))

    /** A number. */
    def number(n: Double): Maybe[Value] = Present(Structure.Value.Decimal(n))

    /** An integer. */
    def integer(n: Long): Maybe[Value] = Present(Structure.Value.Integer(n))

    /** A boolean. */
    def boolean(b: Boolean): Maybe[Value] = Present(Structure.Value.Bool(b))

    extension (v: Value)
        /** The field `name` of an object. */
        def field(name: String): Maybe[Value] = v match
            case Structure.Value.Record(fs) =>
              Maybe.fromOption(fs.find(_._1 == name).map(_._2))
            case _                          => Absent

        /** The value at the path of fields `names`. */
        def at(names: String*): Maybe[Value] =
          names.foldLeft(Present(v): Maybe[Value])((m, n) =>
            m.flatMap(_.field(n)),
          )

        /** The element `i` of an array. */
        def index(i: Int): Maybe[Value] = v match
            case Structure.Value.Sequence(es) => Maybe.fromOption(es.lift(i))
            case _                            => Absent

        /** The text of a string. */
        def str: Maybe[String] = v match
            case Structure.Value.Str(s) => Present(s)
            case _                      => Absent

        /** The value of an integer (a number with no fraction). */
        def int: Maybe[Int] = v match
            case Structure.Value.Integer(n)                => Present(n.toInt)
            case Structure.Value.Decimal(d) if d.isWhole   => Present(d.toInt)
            case Structure.Value.BigNum(b) if b.isValidInt => Present(b.toInt)
            case _                                         => Absent
    end extension
end JsonTree

/** The protocol of a provider: the call of a request, and the fold of its
  * server-sent events into chat events. A pure state machine; [[SseProvider]]
  * runs it on an [[HttpSeam]]. The laws of every wire (`WireTest`, on recorded
  * fixtures): the text deltas of a reply give its text; a reply ends with one
  * [[ChatEvent.Stop]] or one [[ChatEvent.Error]], and nothing comes after it; a
  * stream that ends before the stop gives a [[ChatError.Network]] error.
  */
// claude 2026-10-19: created (L3)
trait Wire:
    /** The state of the fold. */
    type State

    /** The name of the provider, for the log. */
    def name: String

    /** The call of `request` with the key `key`. */
    def call(request: ChatRequest, key: ApiKey)(using Frame): HttpCall

    /** The state before the first event. */
    def start: State

    /** The state after `event`, and its chat events. */
    def step(state: State, event: SseEvent)(using
        Frame,
    ): (State, Chunk[ChatEvent])

    /** The chat events at the end of the stream: an error when the stop did not
      * come.
      */
    def end(state: State): Chunk[ChatEvent]
end Wire

/** A chat provider over server-sent events: the [[Wire]] of the provider, its
  * key, and the HTTP seam of the platform.
  */
// claude 2026-10-19: created (L3)
object SseProvider:
    /** The provider of `wire` with `key` on `http`. A failure of the seam
      * becomes the [[ChatError]] of [[HttpFailure.toChatError]].
      */
    def make(wire: Wire, key: ApiKey, http: HttpSeam): ChatProvider =
      new ChatProvider:
          def stream(request: ChatRequest)(using
              Frame,
          ): Stream[ChatEvent, Async & Abort[ChatError]] =
            Stream {
              Abort.runWith[HttpFailure](
                http
                  .lines(wire.call(request, key))
                  .fold((Sse.Events.empty, wire.start)) {
                    case ((sse, state), line) =>
                      val (next, found) = Sse.Events.feed(sse, line)
                      found match
                          case Present(e) =>
                            val (after, out) = wire.step(state, e)
                            emit(out).andThen((next, after))
                          case Absent     => (next, state)
                  },
              ) {
                case Result.Success((_, state)) => emit(wire.end(state))
                case Result.Failure(f)          => Abort.fail(f.toChatError)
                case Result.Panic(t)            => Abort.panic(t)
              }
            }

          private def emit(out: Chunk[ChatEvent])(using Frame) =
            if out.isEmpty then Kyo.unit else Emit.value(out)
end SseProvider
