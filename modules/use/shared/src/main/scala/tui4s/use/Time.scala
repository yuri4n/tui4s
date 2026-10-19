package tui4s.use

import kyo.*

/** The current time as a signal. It starts with `Clock.now` and changes at each
  * multiple of `every` since the epoch: a clock of `1.second` changes on the
  * second, so a display of the time does not drift. A period under 1 ms is 1
  * ms. The time comes from Kyo's `Clock`, so a test drives it with
  * `Clock.withTimeControl`. The ticks run in a fiber that ends when the
  * caller's `Scope` closes.
  *
  * {{{
  * def clock: UI < (Sync & Scope) =
  *   for now <- useNow(1.second)
  *   yield span(now.map(_.show))
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useNow(every: Duration)(using Frame): Signal[Instant] < (Sync & Scope) =
  for
      start <- Clock.now
      now   <- Signal.initRef(start)
      _     <- Fiber.init(
                 Loop.forever(
                   Clock.now
                     .map(t => Async.sleep(Ticks.untilNext(t, every)))
                     .andThen(Clock.now.map(now.set)),
                 ),
               )
  yield now

/** How far an instant is from now, in its largest whole unit: the value that
  * [[timeAgo]] formats. [[TimeAgo.english]] gives English text.
  */
// claude 2026-10-19: created (L2a)
enum TimeAgo derives CanEqual:
    /** Less than 10 seconds before or after now. */
    case JustNow

    /** `amount` units before now. */
    case Ago(amount: Long, unit: TimeAgo.Scale)

    /** `amount` units after now. */
    case In(amount: Long, unit: TimeAgo.Scale)
end TimeAgo

/** The units, the rule of [[TimeAgo.between]] and the English text. */
// claude 2026-10-19: created (L2a); changed: Scaladoc (L2a review)
object TimeAgo:
    /** The unit of a [[TimeAgo]]. A month is 30 days and a year is 365 days. */
    enum Scale derives CanEqual:
        case Second, Minute, Hour, Day, Week, Month, Year

    /** How far `at` is from `now`. Under 10 seconds is [[TimeAgo.JustNow]].
      *
      * {{{
      * TimeAgo.between(now - 90.seconds, now) // Ago(1, Scale.Minute)
      * }}}
      */
    def between(at: Instant, now: Instant): TimeAgo =
      now.minus(at) match
          case Present(past) => scaled(past).fold(JustNow)((n, s) => Ago(n, s))
          case Absent        =>
            scaled(at.minusOrZero(now)).fold(JustNow)((n, s) => In(n, s))

    /** The amount and the unit of `d`, or Absent under 10 seconds. */
    private def scaled(d: Duration): Maybe[(Long, Scale)] =
        val s = d.toSeconds
        if s < 10 then Absent
        else if s < 60 then Present((s, Scale.Second))
        else if s < 3600 then Present((s / 60, Scale.Minute))
        else if s < 86400 then Present((s / 3600, Scale.Hour))
        else if s < 7 * 86400 then Present((s / 86400, Scale.Day))
        else if s < 30 * 86400 then Present((s / (7 * 86400), Scale.Week))
        else if s < 365 * 86400 then Present((s / (30 * 86400), Scale.Month))
        else Present((s / (365 * 86400), Scale.Year))
        end if
    end scaled

    /** English text: "just now", "1 minute ago", "in 3 days". */
    def english(t: TimeAgo): String = t match
        case JustNow      => "just now"
        case Ago(n, unit) => s"${amount(n, unit)} ago"
        case In(n, unit)  => s"in ${amount(n, unit)}"

    private def amount(n: Long, unit: Scale): String =
        val name = unit.toString.toLowerCase
        if n == 1 then s"1 $name" else s"$n ${name}s"
end TimeAgo

/** The text of how long ago `at` was, from the time `now` (for example the
  * signal of [[useNow]]). It changes when either signal changes. `format` gives
  * the text; the default is English ([[TimeAgo.english]]). It is a pure
  * transform of two signals with no lifetime, so it has no `use` name.
  *
  * {{{
  * def stamp(updated: Signal[Instant]): UI < (Sync & Scope) =
  *   for now <- useNow(1.second)
  *   yield span(timeAgo(updated, now).map(t => s"updated $t"))
  * }}}
  */
// claude 2026-10-19: created (L2a; the name: L2a review)
def timeAgo(
    at: Signal[Instant],
    now: Signal[Instant],
    format: TimeAgo => String = TimeAgo.english,
)(using Frame): Signal[String] =
  at.combineLatest(now).map((a, n) => format(TimeAgo.between(a, n)))

/** The tick rule of [[useNow]]. */
// claude 2026-10-19: created (L2a)
private[use] object Ticks:
    /** The time from `t` to the next multiple of `every` (at least 1 ms) since
      * the epoch; a full period when `t` is on a multiple.
      */
    def untilNext(t: Instant, every: Duration): Duration =
        val period = every.max(1.millis).toNanos
        Duration.fromNanos(period - Math.floorMod(t.toDuration.toNanos, period))
