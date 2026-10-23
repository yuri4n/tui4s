# Event-driven systems

How to design, build and operate event-driven Scala services. Source: Volpe,
*Functional Event-Driven Architecture* (FEDA, 2023), which builds a trading
system on Apache Pulsar with fs2, plus a Kafka interpreter. The design rules
hold for any broker and effect system; map the libraries with
`effect-systems.md`. Items marked "(practice)" are not from the book.

## Contents

1. Should you use EDA?
2. Messages
3. Delivery and idempotency
4. Atomicity across stores
5. Designing one service
6. Subscriptions, scaling and single writers
7. State over time: compaction, snapshots, replay
8. Schema evolution
9. Broker abstraction and choice
10. Failure scenarios and remedies
11. Testing and deployment
12. Translating to Kafka and to ZIO

## 1. Should you use EDA?

Source: FEDA ch. 1.

- Decide from the **non-functional requirements** (throughput, availability,
  audit, replay). If a monolith meets the SLA, EDA is overkill; say so and do
  not add a broker.
- Good reasons: demanding availability, auditability, observability, state
  replay. Costs: more moving parts, harder observability, coordinated
  roll-outs, eventual consistency.
- **CQRS** (separate write and read sides) and **event sourcing** (state as a
  left fold over an append-only log) are different tools: pick one, both or a
  hybrid. Event sourcing needs no framework; a pure fold of events into state
  is enough.
- **Request-response operations stay synchronous** (sign-in, an ATM
  withdrawal that must check funds now): process them directly, then publish
  the fact (`SignedIn`) for downstream consumers. Do not wrap them in a
  command and wait for a reply event.
- **Fan-out on a domain event:** the critical service does only the critical
  work and emits one event; side tasks (counters, notifications, audit)
  become consumers that scale on their own. This removes the question of
  which status code to return when a late step of a synchronous HTTP chain
  fails.
- **Listen to yourself:** a service may consume its own events in a concurrent
  stream. You get the decoupling now and can move the consumer to its own
  service later.

## 2. Messages

Source: FEDA ch. 1, unless noted.

- An **event** is a past fact; consumers only react. A **command** is a request
  that produces zero or more events. A **query** produces none.
- Every message carries **its own ID, a correlation ID** (copied from its
  cause and shared by the whole business transaction) **and a creation
  time**. Put the common fields as abstract members of the enum (also FEDA
  ch. 6).
- When one command yields exactly one event, **reuse the command ID as the
  event ID**, so a redelivered command hits a unique constraint (FEDA ch. 7).
- **Emit failure events too** (`SignInFailed`, `NotRegistered`): new
  consumers can then react to failures as well. Queries produce no events;
  the HTTP layer is the query handler (also FEDA ch. 6).
- Split interfaces into reader and writer (`UserCacheReader`,
  `UserCacheWriter`) when only one service may write.
- Version public HTTP APIs in the URL from the first day (FEDA ch. 3).

## 3. Delivery and idempotency

Sources: FEDA ch. 2-3; handler outcomes ch. 5-7.

| Guarantee | How | Use when |
|---|---|---|
| at most once | no acks | a newer message supersedes the older one (latest values, IoT) |
| at least once | acks and retries | the default; pair it with idempotent handlers |
| exactly once | not achievable end to end; approximate with broker transactions, deduplication and idempotent side effects | |

- **Assume at-least-once delivery and make every handler idempotent:**
  deterministic event IDs, unique constraints, deduplication tables, natural
  keys or upserts.
- **Commands that undo or settle something name it.** A release, expire or
  confirm command carries the ID (or version) of the reservation it acts on,
  so a stale or redelivered command cannot act on a newer one (practice).
- **A hold expires.** A reservation that nobody confirms or releases (the
  order was lost, the customer left) holds stock forever. Give it an expiry
  (an `expires_at` swept by a job, or a delayed expire command), and let the
  first of confirm, release and expire win, with a conditional update on
  the reservation's state (practice).
- **Deduplication strategies:**

  | Situation | Strategy |
  |---|---|
  | one producer that retries | broker producer deduplication (sequence IDs) with a stable, unique producer name |
  | a fixed number of producers, each uniquely named | still broker deduplication |
  | a variable number of producers, or crash risk in consume-process-produce | broker transactions; otherwise consumer-side deduplication |
  | consumer-side deduplication | a registry of processed IDs over a time window (for example 30 minutes); in memory for one active consumer, persisted for shared or key-shared subscriptions |

- Broker deduplication and transactions are often **off by default** on the
  server and the client (Pulsar); enable them explicitly.
- **Classify handler outcomes:**
  - expected business error (duplicate name, missing entity): log and ack (a
    retry will not help);
  - duplicate of an already processed message: ack;
  - unexpected error: nack (retry later);
  - undecodable message: decode-error policy plus a dead-letter topic after N
    redeliveries; ack (drop) only when newer messages supersede it;
  - rejected or ignored inputs that produce no output: still ack them.
- **Bound the retries of every error class.** When the retries of one
  message run out, park it (a retry or dead-letter topic, with an alert) and
  continue with the other messages (practice; § 10).
- **A parked message keeps its place in the key's order.** When order per
  key matters (stock per SKU), a later message of the same key must not
  overtake the parked one. Park the later messages of that key behind it
  until someone settles the first one, and let the other keys continue. To
  pause the whole partition is simpler, but it also stops the other keys in
  it. Alert when a key or a partition stops moving (practice).

## 4. Atomicity across stores

Sources: FEDA ch. 2 (the patterns) and ch. 6 (a handler that uses them).

| Need | Tool | Cost |
|---|---|---|
| atomic update inside one process | `Ref` (compare-and-swap), `Semaphore` | none |
| consume + produce + ack atomically | broker transactions (Kafka, Pulsar) | latency; broker operations only |
| database write + event | transactional outbox read by CDC (Debezium) | an extra process; the database can become the bottleneck |
| several writes that can be undone | saga with compensations | complexity |
| every step idempotent | retry everything | design effort |
| exclusive access across processes | distributed lock (Redis, ZooKeeper) | TTL tuning; fencing |

- **Broker transactions cover only broker operations**, never your database
  or cache.
- **Never pair a database transaction with a broker transaction** as two
  resources (`(brokerTx, dbTx).tupled.use`): one commit can still fail and
  atomicity is lost. Use the outbox. Say why in a design: a publish after the
  commit is lost if the process stops between the two; a publish before the
  commit announces a change that a rollback then undoes.
- **Change data capture:** write only to the database; a CDC connector reads
  the transaction log and publishes the event; other writes (Redis) become
  consumers of that event, which gives a linear chain.
- **Transactional outbox:** in one database transaction, insert the entity and
  an `outbox` row; CDC publishes the row. Use it sparingly: every event then
  costs a write to the one database, so that database limits throughput
  again. Listen-to-yourself is often lighter.
- **Outbox relay transactions are short.** Do not hold a database
  transaction (or a row lock) open while you publish to the broker. Claim a
  batch in one short transaction (`FOR UPDATE SKIP LOCKED`, or a lease
  column), publish outside it, and mark the rows sent in a second
  transaction. A crash between the steps republishes, which is safe because
  consumers are idempotent (practice; FEDA uses CDC for this step).
  - Make the lease longer than the worst case for the whole claimed batch
    (batch size times the publish timeout), or lease and mark row by row;
    otherwise a second relay claims the rows again and publishes duplicates.
  - Classify publish errors. A transient one (a timeout, the broker
    unavailable) gets a capped backoff, and the row stays pending; a timeout
    is an unknown outcome, and the republish is safe. Do not park rows in a
    broker outage, because each parked row then needs a manual replay;
    alert on the age of the oldest pending row instead. A rejection is
    about this record (too large, a serialization or schema error) and
    does not get better: mark the row parked at the first rejection and
    alert. An authorization or authentication error, or an unknown topic,
    is a setup error that fails every row: keep the rows pending and alert,
    as in an outage, or every row is parked (practice).
  - When order per key matters, a row that fails or is parked must block the
    later rows of the same key, and two relays must not publish rows of one
    key at the same time (for example, run one relay per key range).
- **Database write inside a broker-transaction handler:** make it idempotent
  (natural primary key, upsert) or use CRDTs, because the broker transaction
  may abort after the write succeeded.
- **Distributed lock** on Redis: see `http-and-persistence.md`; release with
  an atomic compare-and-delete, and use fencing tokens if two holders for a
  moment would corrupt data (practice).

## 5. Designing one service

Ask these questions for every service. FEDA ch. 6 works through them for the
trading services; other chapters are noted.

1. **Inputs and outputs:** which commands or events, from which topics, with
   which subscription types; which events or alerts, to which topics, with
   which keys.
2. **Critical or not?** A non-critical consumer may be down and catch up from
   the log. Give stateless critical services extra instances (FEDA ch. 2).
3. **Stateless or stateful?** A service is stateful only if it needs state
   from storage to start. A compacted topic or a snapshot often removes that
   need. Writing to a database does not make a service stateful, and neither
   does local state that a framework restores for you (Kafka Streams tables);
   ask who restores the state at start. Prefer stateless processors with the
   state in the broker, and a separate sink service that persists results
   (FEDA ch. 3). Before you scale out a database writer, ask whether writes
   contend; if they do, use one active writer (Failover, as FEDA ch. 7 does
   for its forecasts store) or partition the writes by key. Write per
   command at low traffic; batch at high traffic (FEDA ch. 2, 5).
4. **Tables first:** write (state, input) -> output and (state, input) ->
   state change as tables; each row becomes one case of a pure FSM. Not
   every service needs an FSM: a stateless command handler over database
   stores with an outbox is fine for low traffic (FEDA ch. 7).
5. **"What if?" (FEDA's phrase) for every effect in the handler:** what if
   this line fails? What if it succeeds and the reply is lost? Is the retry
   safe? Write the answers in a design note.
6. **Handler shape** for "process a command, publish an event": one broker
   transaction per command; run the pure FSM; create the event ID and time;
   publish with the transaction; ack with the transaction; return the new
   state. On any error, log and **keep the old state**; the transaction aborts
   and the command is redelivered:

   ```scala
   def onCommand(seats: Seats, in: Delivery[BookingCommand]): F[Seats] =
     broker.transaction.use { tx =>
       val (after, toEvent) = Booking.decide(seats, in.body)   // pure FSM step
       for
         id  <- UUIDGen[F].randomUUID.map(EventId(_))
         now <- Clock[F].realTimeInstant
         _   <- events.publish(toEvent(id, now), tx)
         _   <- commands.ack(in.id, tx)
       yield after
     }.handleErrorWith(e => Logger[F].warn(s"tx aborted, old state kept: $e").as(seats))
   // uses the transactional variants of § 9
   ```

7. **Scaling and start-up:** which key shards the work, what happens on a
   rebalance, where the start state comes from, and whether replay order
   matters between topics (sections 6 and 7).
8. **Pipelines:** keep real-time work, batching and analytics in separate
   stages or consumers; batch slow writes (`chunkN`, `groupWithin`) (FEDA
   ch. 5). Do not put an in-memory hop (an fs2 `Topic` or `Queue`) between
   consume and ack when messages matter: with manual acks, a failed ack
   duplicates downstream (FEDA ch. 6); with auto-ack, a crash loses
   everything buffered in memory (practice). Use an in-memory fan-out only
   for data you can lose or recompute; otherwise give each branch its own
   subscription, or ack after all branches finish (practice).

## 6. Subscriptions, scaling and single writers

Sources: FEDA ch. 3 (subscription types) and ch. 6-8 (the services), as
noted.

Choose the Pulsar subscription type by need (FEDA ch. 3):

| Need | Type | Consumers | Delivery |
|---|---|---|---|
| every service gets every message (pub-sub) | Exclusive, one subscription per service | one | all messages, in order |
| one active instance with a hot standby | Failover | many attached, one active | in order; a standby takes over |
| a stateless work queue | Shared | many | round robin, no order |
| parallel work with order per key | Key_Shared | many | by ordering key |

Scaling a consumer of keyed events (FEDA ch. 6):

| Service kind | Subscription | Rebalance handling |
|---|---|---|
| output depends only on a small global state | Key_Shared by entity key + an Exclusive subscription per instance on a compacted control topic | nothing; start empty and read the control state |
| per-key aggregate state | Key_Shared + share state updates between instances on a compacted topic, tagged with the instance ID | each instance applies other instances' updates |
| per-key state, simple operations | Failover | none; no horizontal scale on one topic |
| per-key state, fixed fleet | Key_Shared with sticky hashing | none; fixed count; stalls if one instance is down (dated detail) |
| must see every message (control and state topics) | Exclusive per instance with a unique name | not applicable |

- **Subscription names decide who shares a cursor:** instances of a
  Key_Shared, Shared or Failover consumer use the service name; per-instance
  Exclusive consumers use the instance ID; per-connection consumers use
  generated names with non-durable mode. An Exclusive subscription with a
  global name allows only one instance (FEDA ch. 6-8).
- **Single writer** (one active writer at a time) (FEDA ch. 7):
  - stateless writer: a Failover subscription;
  - stateful writer that must load a snapshot before processing: an
    Exclusive, non-durable subscription per instance plus a distributed lock;
    only the lock holder loads the snapshot, rewinds and processes. A
    Failover standby gets no "you are now active" signal, so it cannot
    prepare its state.
- **A command that touches several keys** (an order with many SKUs) cannot
  be routed to one per-key owner. Choose and state one approach:
  - split it into per-key commands and coordinate the results (a saga with a
    release step);
  - key by the command's own ID and serialize per key in the database (lock
    the rows in a fixed order, with a `CHECK` constraint as the last guard);
  - route it to the owner of a group key when keys never cross groups.
  The single-writer rules above apply to the first and third choices; the
  second moves the single-writer guarantee into the database (practice).
- **Periodic writers** (snapshots): keep pending message IDs in the FSM state,
  write on a tick, ack in a batch after a successful write, keep the pending
  IDs when the write or the ack fails. A tick stream can also refresh the
  lock's TTL (FEDA ch. 7).
- **Per-client fan-out** (WebSocket clients): one non-durable, exclusive
  broker subscription per connection, reading the compacted topic from the
  earliest message, so the broker handles deduplication. Remove it when the
  client disconnects. A single topic may then become a bottleneck; start with
  a partitioned topic of one partition (FEDA ch. 6).

## 7. State over time: compaction, snapshots, replay

Source: FEDA ch. 3, unless noted.

- **Compaction:** give each message a key; the broker keeps only the newest
  message per key. Consumers rebuild latest-value state quickly. Never compact
  the event-sourced audit log.
- **Snapshots with short retention:** a dedicated service periodically writes
  the state plus the ID of the last event it includes; services load the
  latest snapshot and replay only later events. Keep long-term history in a
  warehouse or database, not in a huge topic.
- **Rewind and gate:** when a service replays one topic from a snapshot and
  also reads compacted topics, hold the compacted consumers behind a
  `Deferred` gate that the replay stream completes when it reaches the last
  message ID present at start-up. Otherwise the latest values are applied to
  old state (FEDA ch. 6). Handle the edge case where there is no last
  message ID, or the gate never opens (practice).
- **Pulsar key naming:** the ordering key routes Key_Shared messages; the
  message key drives compaction (and routing when there is no ordering key)
  (FEDA ch. 5).
- **Choose the compaction key by asking "the latest value of what?"** Keying
  switch messages by kind ("start", "stop") keeps one message per kind and
  then depends on log order to know the current state; a single key for "the
  current switch state" would not (FEDA ch. 3, 6; practice).
- **Retention must cover every planned replay.** By default Pulsar keeps only
  unacknowledged messages, so snapshot rewinds, a first start from the
  earliest message and non-durable subscriptions see only what is retained.
  Set retention to at least the snapshot interval plus the longest outage
  plus a margin, keep the snapshot key's TTL above the same window, and alert
  on snapshot age (practice; FEDA ch. 3, 6-7 show the replays).

## 8. Schema evolution

Source: FEDA ch. 3, unless noted.

- Plan schema changes in the design phase; a later fix is expensive.
- **Compatible changes:** add fields as optional with defaults (`Option` in
  shared Scala models; in Avro a union with null and a default). Across teams
  or languages, use a schema format (Avro, Protobuf) with a registry that
  checks compatibility at start-up and keep the schemas in version control.
  Backward compatibility (new readers read old data) is usually enough for
  messaging.
- **Breaking changes:** publish to a new versioned topic. Deploy the consumer
  that reads both versions first, then the producer, then remove the old code
  and topic. Deploying the producer first creates a backlog with no consumer.
- During rolling deployments, message schemas must stay backward compatible
  (FEDA ch. 8).
- **JSON bytes get no registry check.** The book's services exchange Circe
  JSON as bytes, so nothing checks compatibility at start-up. Within one team
  and a shared domain module, use codec round-trip and golden tests plus
  consumer-first deployments; across teams or languages, use Avro or
  Protobuf with a registry (FEDA ch. 3, 5-6, 8).

## 9. Broker abstraction and choice

- Small transport algebras (publisher, subscriber, acknowledgements) with an
  in-memory interpreter are good for tests, prototypes and house defaults
  (structured logging, dead-letter policy) (FEDA ch. 5):

  ```scala
  final case class Delivery[A](id: MessageId, headers: Map[String, String], body: A)

  trait Publisher[F[_], A]:
    def publish(a: A, headers: Map[String, String] = Map.empty): F[Unit]

  trait Acks[F[_]]:
    def ack(id: MessageId): F[Unit]
    def nack(id: MessageId): F[Unit]                 // ask for redelivery

  trait Subscriber[F[_], A] extends Acks[F]:
    def deliveries: Stream[F, Delivery[A]]           // you ack each one
    def autoAcked: Stream[F, A]                      // acked on receipt

  // transactional variants take the broker's transaction handle:
  //   publish(a, tx), ack(id, tx)
  ```

- **Do not promise that Kafka and Pulsar are interchangeable.** Their semantics
  differ (no nack in Kafka consumer groups, offsets vs message IDs, keys vs
  ordering keys, subscription types). Pick the broker, learn its semantics,
  and use its library directly where they differ (FEDA ch. 5).
- Volpe's comparison (he maintains a Pulsar client, a declared bias; FEDA
  ch. 3): he rates ZooKeeper-based Kafka as the most proven in production
  (he had no KRaft experience), with Kafka Streams and Connect; Pulsar has
  easier defaults, optional partitions, subscription types, a built-in schema
  registry, geo-replication and tiered storage. The book expects ZooKeeper's
  removal in Kafka 4. Dated: Kafka 4.0 (2025) removed it, and share groups
  (production-ready from Kafka 4.2) narrow the gap with Pulsar's shared
  subscriptions; check current versions (practice).

## 10. Failure scenarios and remedies

Each row cites the FEDA chapter that discusses it; "practice" rows are not
from the book.

| Scenario | Remedy | FEDA ch. |
|---|---|---|
| synchronous HTTP chain: the main step succeeds, a side step fails | publish an event; side steps become consumers | 1 |
| a non-critical consumer is down | eventual consistency; it catches up from the log | 1-2 |
| a remote call is retried after a timeout or crash | an idempotent API (409 returns the existing ID) | 2 |
| a producer retry creates duplicates | broker deduplication with stable, unique producer names | 2-3 |
| a variable number of producers, or producers without stable unique names, on one topic | broker transactions, or a consumer-side ID registry over a time window | 2-3 |
| crash after produce, before ack | produce and ack in one broker transaction; otherwise consumer deduplication | 3, 6 |
| the input was processed but its ack was lost | at-least-once delivery and idempotent handlers | 3 |
| write to the database and the cache and publish, with no atomicity | CDC, outbox, listen-to-yourself, saga, or idempotent steps | 2 |
| a database write inside a broker-transaction handler fails, or succeeds with a lost reply | idempotent write; never pair the transactions; outbox | 6 |
| a command is redelivered after the database commit | event ID = command ID; unique constraint; ack the duplicate | 7 |
| a side effect must happen once per event (a vote) | a deduplication table keyed by event ID in the same database transaction | 7 |
| the handler's transaction fails | keep the previous in-memory state; the message is redelivered | 6 |
| an undecodable message | decode-error handler and a dead-letter topic | 5 |
| a handler's retries run out for one message | park the message (retry topic or dead letter with an alert) and continue; do not fail the whole consumer stream, which restarts every partition on that instance; when order per key matters, park the later messages of that key too (§ 3) | practice |
| the outbox relay cannot publish one row | keep the row pending on a transient or setup error (authorization), with a capped backoff and an alert on the oldest pending row; park a rejected row at once, with an alert; later rows of its key wait (§ 4) | practice |
| a breaking schema change | versioned topic; consumers first | 3 |
| a rebalance in a stateful sharded service gives stale state for new keys | share state updates between instances, use Failover, or a fixed fleet | 6 |
| start-up replays too much history | compaction; snapshots with short retention | 3 |
| replay from a snapshot races with compacted-topic consumers | a `Deferred` gate opened at the last message ID | 6 |
| a Failover standby takes over with stale state | Exclusive per instance + distributed lock; load and rewind first | 7 |
| a snapshot write or a batch ack fails | keep the pending IDs; retry on the next tick | 7 |
| an in-memory rebroadcast between consume and ack duplicates on ack failure | one broker subscription per client | 6 |
| a fast producer and a slow database | batch writes; separate real-time and batch stages | 5 |
| related messages from different topics arrive out of order | buffer and match by correlation ID; re-run pending items on a tick; expire unmatched entries | 8 |
| a second replica of a consumer with an exclusive global name | it crash-loops; give unique names or use Failover | 8 |

## 11. Testing and deployment

- **Test pyramid** (FEDA ch. 6-8): pure FSM tests with scripted inputs;
  handler tests with in-memory fakes, including failing fakes; integration
  tests per store interpreter (Redis; SQL with migrations); smoke tests of a
  few services through the real broker in CI. No full-system simulation. See
  `testing.md`.
- **Feed services** generate realistic traffic from ScalaCheck generators
  (weighted with `Gen.frequency`, referencing existing IDs) for manual tests
  (FEDA ch. 7).
- **Deployment follows subscription types** (FEDA ch. 8):
  - Key_Shared and unique exclusive subscriptions scale with replicas;
  - an exclusive subscription with a global name is a single-instance service
    by design (a second pod crash-loops), and rolling restarts cause seconds
    of downtime, because the old pod must stop first. The book leaves the
    Kubernetes rollout strategy and readiness probes unshown; use a
    "Recreate" strategy (no surge), or a Failover subscription with two or
    more replicas (practice);
  - Failover services move traffic to the standby when the active pod dies.
- Every service exposes health and metrics, and logs every produced and
  consumed message in a structured way (see `assembly-and-operations.md`).

## 12. Translating to Kafka and to ZIO

The concepts map, but the semantics differ; verify for the Kafka version in
use (practice):

| Pulsar (book) | Kafka |
|---|---|
| Key_Shared by entity key | partitions keyed by entity, one consumer group |
| Exclusive subscription per instance | a unique consumer group per instance |
| Failover | one active consumer per partition in a group |
| broker transactions | Kafka transactions (exactly-once within Kafka) |
| compaction | log compaction |
| last message ID, rewind to a message ID | offsets: `seek`, `endOffsets` |
| Shared subscription | share groups (Kafka 4.2+, production-ready; use 4.2.1 or later) |

- Libraries: fs2-kafka (Cats Effect; `org.typelevel` from 4.x), ZIO Kafka
  (ZIO), the Ox Kafka module (direct style); Neutron (Pulsar, Cats Effect;
  `dev.profunktor`, no release since 0.8.0 in Sep 2023, on Pulsar 3.1; prefer
  pulsar4s-cats-effect / pulsar4s-fs2, or the Java client behind an algebra).
  Use the Scala Compendium (see `effect-systems.md`).
- ZIO: FSMs run with `ZStream.mapAccumZIO`; `Resource` becomes `Scope` or a
  scoped layer; `Deferred` becomes `Promise`; `Ref` stays `Ref`.
- Kafka consumer groups have no nack: retry topics, dead-letter topics and
  offset commits replace it. Auto-commit conflicts with manual-ack designs
  (FEDA ch. 5). Share groups (Kafka 4.2+) do have per-record
  acknowledgements (`RELEASE` redelivers, `REJECT` drops).
