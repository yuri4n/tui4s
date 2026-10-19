package examples.assistant

import kyo.*

/** The providers that the app can use. */
// claude 2026-10-19: created (L3)
enum ProviderChoice derives CanEqual:
    /** The Messages API of Anthropic, with an API key. */
    case Anthropic

    /** The Chat Completions API of OpenAI, with an API key. */
    case OpenAI

    /** The scripted fake provider: no network, no key (the tests, the demo). */
    case Fake

    /** The name of the provider for the person. */
    def label: String = this match
        case Anthropic => "Anthropic"
        case OpenAI    => "OpenAI"
        case Fake      => "Fake"

    /** The model that the app takes for this provider ([[Models]]). */
    def defaultModel: String = this match
        case Anthropic => Models.anthropic
        case OpenAI    => Models.openai
        case Fake      => Models.fake

    /** The text of the provider in a settings file. */
    def code: String = this match
        case Anthropic => "anthropic"
        case OpenAI    => "openai"
        case Fake      => "fake"
end ProviderChoice

/** Reads a [[ProviderChoice]]. */
// claude 2026-10-19: created (L3)
object ProviderChoice:
    /** The provider of the text `code` (see `ProviderChoice.code`). */
    def parse(code: String): Maybe[ProviderChoice] =
      Maybe.fromOption(values.find(_.code == code))

/** The default model of each provider: the one place of the model ids. They are
  * data: the person can change the model in the settings.
  */
// claude 2026-10-19: created (L3)
object Models:
    /** The latest Claude model (the Messages API of Anthropic). */
    val anthropic: String = "claude-opus-5-5"

    /** A Chat Completions model of OpenAI. */
    val openai: String = "gpt-5"

    /** The model name of the fake provider (it has no model). */
    val fake: String = "fake-1"
end Models

/** The settings of the app: the provider, its model, the temperature (Absent:
  * the default of the provider) and the system prompt. A key is never a
  * setting: [[Secrets]] gives the keys, and the settings never show them.
  */
// claude 2026-10-19: created (L3)
final case class Settings(
    provider: ProviderChoice,
    model: String,
    temperature: Maybe[Double],
    systemPrompt: String,
) derives CanEqual

/** The default settings, the start rule, and the temperature rule. */
// claude 2026-10-19: created (L3)
object Settings:
    /** The system prompt of a new install. */
    val defaultSystemPrompt: String =
      "You are a helpful assistant in a terminal. Answer in plain text."

    /** The settings of `p` with its default model. */
    def of(p: ProviderChoice): Settings =
      Settings(p, p.defaultModel, Absent, defaultSystemPrompt)

    /** The settings at the start of the app. `forced` (the flag `--fake`) wins.
      * Else the stored settings, when their provider is in `available` (it has
      * a key). Else the first provider of `available` in the order Anthropic,
      * OpenAI, Fake. A change of the provider takes its default model, and
      * keeps the stored temperature and system prompt.
      */
    // claude 2026-10-19: changed: a new provider keeps the prompt (L3 review)
    def pick(
        stored: Maybe[Settings],
        available: Chunk[ProviderChoice],
        forced: Maybe[ProviderChoice],
    ): Settings =
        def keep(p: ProviderChoice): Settings =
          stored.fold(of(p))(s =>
            if s.provider == p then s
            else s.copy(provider = p, model = p.defaultModel),
          )
        forced match
            case Present(p) => keep(p)
            case Absent     =>
              stored.filter(s => available.contains(s.provider)).getOrElse {
                val first = ProviderChoice.values.find(available.contains)
                keep(first.getOrElse(ProviderChoice.Fake))
              }
    end pick

    /** The temperature of the text of a field: an empty text is Absent (the
      * default of the provider); else a number from 0 to 2, or the reason why
      * the text is not one.
      */
    def temperature(text: String): Either[String, Maybe[Double]] =
        val t = text.trim
        if t.isEmpty then Right(Absent)
        else
            t.toDoubleOption match
                case Some(d) if d >= 0 && d <= 2 => Right(Present(d))
                case _                           =>
                  Left(s"the temperature is a number from 0 to 2, not '$t'")
    end temperature
end Settings
