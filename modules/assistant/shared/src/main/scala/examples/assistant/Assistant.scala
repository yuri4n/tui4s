package examples.assistant

import kyo.*
import tui4s.use.{Edge, Selection, Storage, usePersisted, useSelection}

/** How the app picks its providers at the start. */
// claude 2026-10-19: created (L3)
enum StartMode derives CanEqual:
    /** Reads the keys: each provider with a key is usable, and the fake. */
    case Detect

    /** The flag `--fake`: reads no key; only the fake provider. */
    case FakeOnly

/** Where the reply is: no reply streams, or the reply of the conversation
  * `chat` streams.
  */
// claude 2026-10-19: created (L3)
enum Streaming derives CanEqual:
    /** No reply streams. */
    case Idle

    /** The reply of `chat` streams. */
    case Running(chat: ConversationId)

/** The layer over the app, besides the palette and the questions. */
// claude 2026-10-19: created (L3)
enum Overlay derives CanEqual:
    /** No layer. */
    case Closed

    /** The settings. */
    case Settings

    /** The help. */
    case Help
end Overlay

/** Whether the log pane shows. */
// claude 2026-10-19: created (L3)
enum Pane derives CanEqual:
    /** The pane is hidden. */
    case Hidden

    /** The pane shows. */
    case Shown

/** The services of the app as Kyo layers: the main of a platform gives the
  * storage and the keys of its platform, and these build the rest. A test gives
  * the interpreters in memory ([[Wiring.inMemory]]).
  */
// claude 2026-10-19: created (L3)
object Wiring:
    /** The services that the app reads from its environment. */
    type Services = ConversationStore & Storage & Providers & AppLog

    /** The store of the conversations over the storage of the platform. */
    def conversations(using Frame): Layer[ConversationStore, Env[Storage]] =
      Layer.from((s: Storage) => ConversationStore.make(s))

    /** The log of the app. */
    def log(using Frame): Layer[AppLog, Sync] = Layer(AppLog.make())

    // region: wiring
    /** The providers: with [[StartMode.Detect]], each provider whose key
      * `Secrets` finds (`live` makes it); always the fake `fake`.
      */
    def providers(
        live: (ProviderChoice, ApiKey) => ChatProvider,
        fake: ChatProvider,
        mode: StartMode,
    )(using Frame): Layer[Providers, Env[Secrets & AppLog] & Sync] =
      Layer.from[Secrets, AppLog, Providers, Sync]((s, log) =>
        mode match
            case StartMode.Detect   => Providers.make(s, live, fake, log)
            case StartMode.FakeOnly =>
              Providers.fakeOnly(fake): Providers < Sync,
      )
    // endregion: wiring

    /** The storage and the keys in memory (tests): `storage` holds the files,
      * `keys` the keys of the environment.
      */
    def inMemory(
        storage: Storage,
        keys: Map[ProviderChoice, String] = Map.empty,
    )(using Frame): (Layer[Storage, Any], Layer[Secrets, Any]) =
      (Layer(storage), Layer(Secrets.inMemory(keys)))
end Wiring

/** The store of the app: one typed feature that groups its state (the
  * conversations, the selected one, the text of the composer, the stream, the
  * settings, the layers, the log), its derived values (the active conversation,
  * its tokens) and its actions (send, cancel, new, switch, delete, rename, the
  * settings). Make it once, with [[Assistant.make]]: the app owns it, and the
  * end of its `Scope` stops a reply that streams (the reply is marked stopped
  * and saved).
  *
  * A reply runs on a fiber of its own. [[cancel]] interrupts that fiber: the
  * stream, and its connection, stop with it (one resource, one scope). The
  * events of the stream go through [[Transcript]] into the conversation, so the
  * reply grows in place.
  */
// claude 2026-10-19: created (L3)
final class Assistant private (
    chatsRef: SignalRef[Chunk[Conversation]],
    /** The selected conversation of the list: it is the active one. */
    val selection: Selection[Conversation, ConversationId],
    /** The text of the composer (the textarea binds it). */
    val composer: SignalRef[String],
    streamingRef: SignalRef[Streaming],
    settingsRef: SignalRef[Settings],
    /** The layer over the app. */
    val overlay: SignalRef[Overlay],
    /** Whether the log pane shows. */
    val logPane: SignalRef[Pane],
    /** The log of the app. */
    val log: AppLog,
    /** The usable providers. */
    val providers: Providers,
    store: ConversationStore,
    run: AtomicRef[Assistant.Run],
    generation: AtomicLong,
)(using Frame):
    import Assistant.Run

    /** The conversations, the newest first. */
    val chats: Signal[Chunk[Conversation]] = chatsRef

    /** Where the reply is. */
    val streaming: Signal[Streaming] = streamingRef

    /** The settings. */
    val settings: Signal[Settings] = settingsRef

    /** The active conversation: the selected one. */
    val active: Signal[Maybe[Conversation]] =
      chatsRef
        .combineLatest(selection.key)
        .map((cs, k) => k.flatMap(id => Maybe.fromOption(cs.find(_.id == id))))

    /** The tokens of the active conversation. */
    val tokens: Signal[Usage] = active.map(_.fold(Usage.zero)(_.usage))

    /** Sends the text of the composer as a message of the active conversation,
      * and streams the reply. Nothing for an empty text; a warning in the log
      * while a reply streams.
      */
    def send: Unit < Async =
      composer.get.map(text =>
        if text.trim.isEmpty then Kyo.unit
        else
            run.get.map {
              case Run.Busy(_, _, _) =>
                log.warn("a reply streams: Esc stops it")
              case Run.Idle          =>
                selection.key.current.map(
                  _.fold(log.warn("no conversation to send to"))(
                    sendTo(_, text),
                  ),
                )
            },
      )

    /** Adds the message `text` to `id`, and the reply that starts. */
    private def sendTo(id: ConversationId, text: String): Unit < Async =
      for
          now <- Clock.now
          s   <- settingsRef.get
          _   <- composer.set("")
          _   <- update(id)(c =>
                   c.copy(
                     title =
                       if c.title == Title.empty then Title.of(text) else c.title,
                     messages = c.messages :+
                       Message(Role.User, text, now, MessageStatus.Done) :+
                       Transcript.start(now),
                   ),
                 )
          c   <- chatsRef.get.map(_.find(_.id == id))
          _   <- save(id)
          _   <- c.fold(Kyo.unit)(c => start(id, ChatRequest.of(c, s), s))
      yield ()

    // region: cancel
    /** Stops the reply that streams: its fiber ends, the message is marked
      * stopped (its text stays), and the conversation is saved.
      */
    def cancel: Unit < Async =
      run.getAndSet(Run.Idle).map {
        case Run.Idle               => Kyo.unit
        case Run.Busy(id, _, fiber) =>
          for
              _ <- fiber.interrupt
              _ <- fold(id, ChatEvent.Error(ChatError.Cancelled))
              _ <- streamingRef.set(Streaming.Idle)
              _ <- save(id)
              _ <- log.info("the reply was stopped")
          yield ()
      }
    // endregion: cancel

    /** Starts a conversation, or selects the active one when it is still empty. */
    def newChat: Unit < Sync =
      active.current.map {
        case Present(c) if c.messages.isEmpty => selection.select(c.id)
        case _                                =>
          for
              now <- Clock.now
              id  <- ConversationId.fresh
              _   <- chatsRef.updateAndGet(Conversation.empty(id, now) +: _)
              _   <- selection.select(id)
          yield ()
      }

    /** Selects the next conversation of the list. */
    def nextChat: Unit < Sync = selection.moveDown

    /** Selects the conversation before in the list. */
    def previousChat: Unit < Sync = selection.moveUp

    /** Selects the conversation `id`. */
    def switchTo(id: ConversationId): Unit < Sync = selection.select(id)

    /** Deletes the conversation `id` (a reply of it that streams stops first).
      * When the list becomes empty, a new conversation takes its place.
      */
    def delete(id: ConversationId): Unit < Async =
      for
          r    <- run.get
          _    <- r match
                      case Run.Busy(on, _, _) if on == id => cancel
                      case _                              => Kyo.unit
          left <- chatsRef.updateAndGet(_.filterNot(_.id == id))
          _    <- store.delete(id)
          _    <- if left.isEmpty then newChat else Kyo.unit
          _    <- log.info("a conversation was deleted")
      yield ()

    /** Deletes the active conversation. */
    def deleteActive: Unit < Async =
      selection.key.current.map(_.fold(Kyo.unit)(delete))

    /** Gives the conversation `id` the title of `text` (the title rule cuts
      * it); nothing for an empty text.
      */
    def rename(id: ConversationId, text: String): Unit < Sync =
      if text.trim.isEmpty then Kyo.unit
      else update(id)(_.copy(title = Title.of(text))).andThen(save(id))

    /** Renames the active conversation. */
    def renameActive(text: String): Unit < Sync =
      selection.key.current.map(_.fold(Kyo.unit)(rename(_, text)))

    /** Takes the provider `p` with its default model; a warning when it has no
      * key.
      */
    def setProvider(p: ProviderChoice): Unit < Sync =
      providers.get(p) match
          case Absent     => log.warn(s"${p.label} has no key: see the README")
          case Present(_) =>
            settingsRef
              .updateAndGet(_.copy(provider = p, model = p.defaultModel))
              .andThen(
                log.info(s"provider: ${p.label}, model ${p.defaultModel}"),
              )

    /** Takes the model `model` (an empty text changes nothing). */
    def setModel(model: String): Unit < Sync =
      if model.trim.isEmpty then Kyo.unit
      else settingsRef.updateAndGet(_.copy(model = model.trim)).unit

    /** Takes the settings `s` of the settings layer; a provider with no key
      * stays the one before.
      */
    def updateSettings(s: Settings): Unit < Sync =
      settingsRef.get.map(before =>
          val p = if providers.get(s.provider).nonEmpty then s.provider
          else before.provider
          settingsRef
            .set(
              s.copy(
                provider = p,
                model =
                  if s.model.trim.isEmpty then p.defaultModel else s.model.trim,
              ),
            )
            .andThen(log.info(s"settings: ${p.label}, model ${s.model.trim}")),
      )

    /** Shows or hides the log pane. */
    def toggleLog: Unit < Sync =
      logPane.updateAndGet {
        case Pane.Hidden => Pane.Shown
        case Pane.Shown  => Pane.Hidden
      }.unit

    private def update(id: ConversationId)(
        f: Conversation => Conversation,
    ): Unit < Sync =
      chatsRef.updateAndGet(_.map(c => if c.id == id then f(c) else c)).unit

    private def fold(id: ConversationId, e: ChatEvent): Unit < Sync =
      update(id)(Transcript.reply(_, e))

    /** Saves the conversation `id`, when it has a message. */
    private def save(id: ConversationId): Unit < Sync =
      chatsRef.get.map(_.find(c => c.id == id && c.messages.nonEmpty) match
          case Some(c) => store.save(c)
          case None    => Kyo.unit)

    // region: start
    /** Starts the fiber of the reply to `request`. The fiber waits for a gate,
      * so the run state names it before it can end.
      */
    private def start(
        id: ConversationId,
        request: ChatRequest,
        s: Settings,
    ): Unit < Sync =
      providers.get(s.provider) match
          case Absent            =>
            fold(id, ChatEvent.Error(ChatError.Auth))
              .andThen(save(id))
              .andThen(log.warn(s"${s.provider.label} has no key"))
          case Present(provider) =>
            for
                gate  <- Latch.init(1)
                gen   <- generation.incrementAndGet
                fiber <- Fiber.initUnscoped(
                           gate.await.andThen(reply(id, gen, request, provider)),
                         )
                _     <- run.set(Run.Busy(id, gen, fiber))
                _     <- streamingRef.set(Streaming.Running(id))
                _     <- log.info(s"${s.provider.label}: a request to ${s.model}")
                _     <- gate.release
            yield ()
    // endregion: start

    /** The body of the fiber of a reply: the events into the conversation, then
      * the end (an error when the stream failed), then the save.
      */
    private def reply(
        id: ConversationId,
        gen: Long,
        request: ChatRequest,
        provider: ChatProvider,
    ): Unit < Async =
      Abort
        .run[ChatError](provider.stream(request).foreach(e => fold(id, e)))
        .map {
          case Result.Success(_) =>
            ChatEvent.Error(ChatError.Network("the reply ended with no stop"))
          case Result.Failure(e) => ChatEvent.Error(e)
          case Result.Panic(t)   => ChatEvent.Error(ChatError.Network(t.toString))
        }
        .map(end => fold(id, end))
        .andThen(run.getAndUpdate {
          case Run.Busy(_, g, _) if g == gen => Run.Idle
          case other                         => other
        })
        .map {
          // only the fiber that ends its own run finishes it: a cancel
          // finished it already
          case Run.Busy(_, g, _) if g == gen =>
            streamingRef
              .set(Streaming.Idle)
              .andThen(save(id))
              .andThen(ended(id))
          case _                             => Kyo.unit
        }

    /** Logs how the reply of `id` ended. */
    private def ended(id: ConversationId): Unit < Sync =
      chatsRef.get.map(_.find(_.id == id).flatMap(_.messages.lastOption) match
          case Some(m) =>
            m.status match
                case MessageStatus.Failed(e) =>
                  log.warn(s"the reply failed: ${e.describe}")
                case _                       =>
                  log.info(s"the reply is done: ${m.usage.output} tokens")
          case None    => Kyo.unit)
end Assistant

/** Makes the store, and its run state. */
// claude 2026-10-19: created (L3)
object Assistant:
    /** The fiber of the reply that streams, if any: its conversation, its
      * number and the fiber.
      */
    private[assistant] enum Run:
        case Idle
        case Busy(
            chat: ConversationId,
            generation: Long,
            fiber: Fiber[Unit, Any],
        )

    /** The store of the app, from the services of its environment. It loads the
      * stored conversations (a reply that streamed when the app ended is marked
      * stopped; a text that is not a conversation is a warning), and the
      * settings (`Settings.pick`: `mode` [[StartMode.FakeOnly]] forces the fake
      * provider).
      */
    def make(mode: StartMode)(using
        Frame,
    ): Assistant < (Env[Wiring.Services] & Async & Scope) =
        import SettingsCodec.codec
        for
            store     <- Env.get[ConversationStore]
            storage   <- Env.get[Storage]
            providers <- Env.get[Providers]
            log       <- Env.get[AppLog]
            loaded    <- load(store, log)
            now       <- Clock.now
            first     <-
              (if loaded.nonEmpty then loaded
               else
                   ConversationId.fresh
                     .map(id => Chunk(Conversation.empty(id, now)))
              ): Chunk[Conversation] < Sync
            chats     <- Signal.initRef(first)
            selection <- useSelection(chats, _.id, Edge.Clamp)
            stored    <- storage
                           .get("settings")
                           .map(
                             _.flatMap(t =>
                               Maybe.fromOption(SettingsCodec.decode(t).toOption),
                             ),
                           )
            forced     = mode match
                             case StartMode.FakeOnly => Present(ProviderChoice.Fake)
                             case StartMode.Detect   => Absent
            picked     = Settings.pick(stored, providers.available, forced)
            settings  <- usePersisted("settings", picked, storage)
            _         <- settings.set(picked)
            composer  <- Signal.initRef("")
            streaming <- Signal.initRef[Streaming](Streaming.Idle)
            overlay   <- Signal.initRef[Overlay](Overlay.Closed)
            pane      <- Signal.initRef[Pane](Pane.Hidden)
            run       <- AtomicRef.init[Run](Run.Idle)
            gen       <- AtomicLong.init(0L)
            app        = Assistant(
                           chats,
                           selection,
                           composer,
                           streaming,
                           settings,
                           overlay,
                           pane,
                           log,
                           providers,
                           store,
                           run,
                           gen,
                         )
            _         <- Scope.ensure(app.cancel)
            _         <- log.info(
                           s"provider: ${picked.provider.label}, model ${picked.model}",
                         )
        yield app
        end for
    end make

    /** The stored conversations, the newest first. */
    private def load(store: ConversationStore, log: AppLog)(using
        Frame,
    ): Chunk[Conversation] < Sync =
      store.list.map(ids =>
        Kyo
          .foreach(ids)(id =>
            Abort.run[StoreError](store.load(id)).map {
              case Result.Success(c) => c.map(stopped)
              case other             =>
                log
                  .warn(s"a stored conversation cannot be read: $other")
                  .andThen(Absent)
            },
          )
          .map(_.flatMap(_.fold(Chunk.empty[Conversation])(Chunk(_)))),
      )

    /** `c` with a streaming reply marked stopped: the app ended during it. */
    private def stopped(c: Conversation): Conversation =
      c.copy(messages =
        c.messages.map(m =>
          if m.status == MessageStatus.Streaming then
              m.copy(status = MessageStatus.Failed(ChatError.Cancelled))
          else m,
        ),
      )
end Assistant
