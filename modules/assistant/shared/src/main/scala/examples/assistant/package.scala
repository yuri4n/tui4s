package examples

/** The assistant: the flagship example app of tui4s, a chat with a language
  * model (Anthropic, OpenAI, or a scripted fake). It is the example of the
  * shape of a real app (D17):
  *
  *   - The domain is pure: the types ([[Conversation]], [[Message]],
  *     [[ChatEvent]], [[Settings]]), the fold of a streamed reply
  *     ([[Transcript]]), the title rule ([[Title]]) and the text form of a
  *     conversation ([[ConversationCodec]]).
  *   - The services are traits (capabilities): [[ChatProvider]], [[Secrets]],
  *     [[ConversationStore]], [[AppLog]]; Kyo's `Clock` gives the time. Each
  *     has its interpreters in a `make` of its companion, one for each platform
  *     where it touches the platform, and an interpreter in memory for the
  *     tests.
  *   - One store ([[Assistant]]) groups the state, the actions, the derived
  *     values and the effects of the app; the app owns it.
  *   - The UI ([[AssistantUi]]) is plain kyo-ui with the composables of
  *     tui4s-use and the widgets of tui4s-widgets: no API of tui4s-core.
  *   - The main of each platform builds the interpreters (Kyo `Layer`) and runs
  *     the UI: `examples.assistant.AssistantApp` in the terminal.
  *
  * Errors are values: a provider fails with a [[ChatError]] (Kyo
  * `Abort[ChatError]`), the keys with a [[SecretsError]].
  */
// claude 2026-10-19: created (L3)
package object assistant
