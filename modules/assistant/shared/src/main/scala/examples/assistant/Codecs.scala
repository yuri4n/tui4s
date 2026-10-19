package examples.assistant

import kyo.*
import tui4s.use.TextCodec

/** The text form of a conversation for a `Storage` of tui4s-use: a small format
  * of lines, written by hand (the app needs no JSON library for its files). The
  * law (`DomainPropertyTest`): `decode(encode(c))` is `Success(c)` for each
  * conversation `c`.
  *
  * {{{
  * tui4s-assistant-chat 1
  * id Ab3dE5gH9jK1
  * title Hello there
  * created 2026-10-19T10:00:00Z
  * message user 2026-10-19T10:00:01Z 0 0 done
  * text Hello there\nHow are you?
  * message assistant 2026-10-19T10:00:02Z 12 40 failed network the stream was cut
  * text I am fine, th
  * }}}
  *
  * A text field is one line: [[Escape]] writes "\\", a line feed and a carriage
  * return as `\\`, `\n` and `\r`. The status of a message is `done`,
  * `streaming`, or `failed` and the error: `auth`, `cancelled`,
  * `rate-limited <ms or ->`, `network <text>`, `protocol <text>`.
  */
// claude 2026-10-19: created (L3)
object ConversationCodec:
    /** The first line of the text of a conversation. */
    val header: String = "tui4s-assistant-chat 1"

    /** The codec. */
    given codec: TextCodec[Conversation] =
      TextCodec.instance(encode, t => Result.fromEither(decode(t)))

    /** The text of `c`. */
    def encode(c: Conversation): String =
      (Chunk(
        header,
        s"id ${c.id.text}",
        s"title ${Escape(c.title)}",
        s"created ${c.created.show}",
      ) ++ c.messages.flatMap(m =>
        Chunk(
          s"message ${role(m.role)} ${m.at.show} ${m.usage.input} ${m.usage.output} ${status(m.status)}",
          s"text ${Escape(m.content)}",
        ),
      )).mkString("", "\n", "\n")

    /** The conversation of `text`, or the reason why it is not one. */
    def decode(text: String): Either[String, Conversation] =
        val lines = Chunk.from(text.split("\n", -1).nn.map(_.nn)) match
            case ls if ls.lastOption.contains("") => ls.dropRight(1)
            case ls                               => ls
        for
            _        <- Either.cond(lines.headOption.contains(header), (), "no header")
            idText   <- field(lines, 1, "id")
            id       <- ConversationId.parse(idText).toRight(s"bad id: $idText")
            title    <- field(lines, 2, "title").flatMap(Escape.undo)
            created  <- field(lines, 3, "created").flatMap(instant)
            messages <-
              Chunk
                .from(lines.drop(4).grouped(2).toSeq)
                .foldLeft(Right(Chunk.empty): Either[String, Chunk[Message]])(
                  (acc, pair) => acc.flatMap(ms => message(pair).map(ms :+ _)),
                )
        yield Conversation(id, title, messages, created)
        end for
    end decode

    private def role(r: Role): String = r match
        case Role.User      => "user"
        case Role.Assistant => "assistant"

    private def status(s: MessageStatus): String = s match
        case MessageStatus.Streaming => "streaming"
        case MessageStatus.Done      => "done"
        case MessageStatus.Failed(e) =>
          "failed " + (e match
              case ChatError.Auth            => "auth"
              case ChatError.Cancelled       => "cancelled"
              case ChatError.RateLimited(at) =>
                s"rate-limited ${at.fold("-")(_.toMillis.toString)}"
              case ChatError.Network(m)      => s"network ${Escape(m)}"
              case ChatError.Protocol(m)     => s"protocol ${Escape(m)}")

    /** The value of the line `i` of `lines`, which starts with `name` and a
      * space.
      */
    private def field(
        lines: Chunk[String],
        i: Int,
        name: String,
    ): Either[String, String] =
      lines.lift(i) match
          case Some(l) if l.startsWith(name + " ") =>
            Right(l.drop(name.length + 1))
          case other                               => Left(s"line ${i + 1}: no $name: $other")

    private def instant(text: String): Either[String, Instant] =
      Instant.parse(text).toEither.left.map(e => s"bad time $text: $e")

    private def count(text: String): Either[String, Int] =
      text.toIntOption.filter(_ >= 0).toRight(s"bad count: $text")

    private def message(pair: Chunk[String]): Either[String, Message] =
      pair match
          case Chunk(head, body) =>
            head.split(" ", 6).nn.map(_.nn).toList match
                case "message" :: r :: at :: in :: out :: st :: Nil =>
                  for
                      role    <- r match
                                     case "user"      => Right(Role.User)
                                     case "assistant" => Right(Role.Assistant)
                                     case other       => Left(s"bad role: $other")
                      time    <- instant(at)
                      input   <- count(in)
                      output  <- count(out)
                      status  <- parseStatus(st)
                      content <-
                        if body.startsWith("text ") then
                            Escape.undo(body.drop(5))
                        else Left(s"no text after: $head")
                  yield Message(
                    role,
                    content,
                    time,
                    status,
                    Usage(input, output),
                  )
                case _                                              =>
                  Left(s"bad message line: $head")
          case _                 => Left("a message without its text line")

    private def parseStatus(text: String): Either[String, MessageStatus] =
      text.split(" ", 2).nn.map(_.nn).toList match
          case "done" :: Nil           => Right(MessageStatus.Done)
          case "streaming" :: Nil      => Right(MessageStatus.Streaming)
          case "failed" :: rest :: Nil =>
            (rest.split(" ", 2).nn.map(_.nn).toList match
                case "auth" :: Nil                => Right(ChatError.Auth)
                case "cancelled" :: Nil           => Right(ChatError.Cancelled)
                case "rate-limited" :: "-" :: Nil =>
                  Right(ChatError.RateLimited(Absent))
                case "rate-limited" :: ms :: Nil  =>
                  ms.toLongOption
                    .toRight(s"bad delay: $ms")
                    .map(n => ChatError.RateLimited(Present(n.millis)))
                case "network" :: m :: Nil        =>
                  Escape.undo(m).map(ChatError.Network(_))
                case "protocol" :: m :: Nil       =>
                  Escape.undo(m).map(ChatError.Protocol(_))
                case _                            => Left(s"bad error: $rest")
            ).map(MessageStatus.Failed(_))
          case _                       => Left(s"bad status: $text")
end ConversationCodec

/** The text form of the [[Settings]] (never a key: the settings hold none).
  *
  * {{{
  * tui4s-assistant-settings 1
  * provider anthropic
  * model a-model-id
  * temperature -
  * system You are a helpful assistant.
  * }}}
  */
// claude 2026-10-19: created (L3)
object SettingsCodec:
    /** The first line of the text of the settings. */
    val header: String = "tui4s-assistant-settings 1"

    /** The codec. */
    given codec: TextCodec[Settings] =
      TextCodec.instance(encode, t => Result.fromEither(decode(t)))

    /** The text of `s`. */
    def encode(s: Settings): String =
      Chunk(
        header,
        s"provider ${s.provider.code}",
        s"model ${Escape(s.model)}",
        s"temperature ${s.temperature.fold("-")(_.toString)}",
        s"system ${Escape(s.systemPrompt)}",
      ).mkString("", "\n", "\n")

    /** The settings of `text`, or the reason why they are not. */
    def decode(text: String): Either[String, Settings] =
      text.split("\n").nn.map(_.nn).toList match
          case `header` :: p :: m :: t :: sys :: _ =>
            for
                provider <-
                  value(p, "provider").flatMap(c =>
                    ProviderChoice.parse(c).toRight(s"bad provider: $c"),
                  )
                model    <- value(m, "model").flatMap(Escape.undo)
                temp     <- value(t, "temperature").flatMap {
                              case "-" => Right(Absent)
                              case d   =>
                                d.toDoubleOption
                                  .toRight(s"bad temperature: $d")
                                  .map(Present(_))
                            }
                system   <- value(sys, "system").flatMap(Escape.undo)
            yield Settings(provider, model, temp, system)
          case _                                   => Left("not the text of the settings")

    private def value(line: String, name: String): Either[String, String] =
      if line.startsWith(name + " ") then Right(line.drop(name.length + 1))
      else Left(s"no $name: $line")
end SettingsCodec

/** The text form of a list of conversation ids: one id per line. */
// claude 2026-10-19: created (L3)
object IndexCodec:
    /** The codec; a line that is not an id is an error. */
    given codec: TextCodec[Chunk[ConversationId]] = TextCodec.instance(
      _.map(_.text).mkString("\n"),
      t =>
        Chunk
          .from(t.split("\n").nn.map(_.nn))
          .filter(_.nonEmpty)
          .foldLeft(
            Result.succeed(Chunk.empty): Result[String, Chunk[ConversationId]],
          )((acc, l) =>
            acc.flatMap(ids =>
              ConversationId.parse(l) match
                  case Present(id) => Result.succeed(ids :+ id)
                  case Absent      => Result.fail(s"bad id: $l"),
            ),
          ),
    )
end IndexCodec

/** One line of text: `\\`, a line feed and a carriage return become `\\\\`,
  * `\\n` and `\\r`. The law: `undo(apply(s))` is `Right(s)`, and `apply(s)` has
  * no line break.
  */
// claude 2026-10-19: created (L3)
object Escape:
    /** The text of `s` on one line. */
    def apply(s: String): String =
      s.flatMap {
        case '\\' => "\\\\"
        case '\n' => "\\n"
        case '\r' => "\\r"
        case c    => c.toString
      }

    /** The text of the line `s`, or the reason why it is not an escaped text. */
    def undo(s: String): Either[String, String] =
        @scala.annotation.tailrec
        def go(i: Int, out: StringBuilder): Either[String, String] =
          if i >= s.length then Right(out.toString)
          else
              s.charAt(i) match
                  case '\\' =>
                    s.lift(i + 1) match
                        case Some('\\') => go(i + 2, out.append('\\'))
                        case Some('n')  => go(i + 2, out.append('\n'))
                        case Some('r')  => go(i + 2, out.append('\r'))
                        case other      => Left(s"bad escape at $i: $other")
                  case c    => go(i + 1, out.append(c))
        go(0, StringBuilder())
    end undo
end Escape
