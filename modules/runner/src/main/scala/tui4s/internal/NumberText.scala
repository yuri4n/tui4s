package tui4s.internal

/** The text of a number field (kyo-ui's `NumberInput`) and the steps of a
  * number field and of a range (kyo-ui's `RangeInput`). Pure functions.
  *
  * The grammar: an optional minus sign (`-`) first, then digits with at most
  * one decimal point (`.`), as the valid floating-point number of HTML (it has
  * no `+`). A field holds a [[partial]] text while the user types it (`""`,
  * `"-"`, `"1."`, `".5"`); the text is a number when it also has a digit
  * ([[parse]]). Other characters (a letter, a second sign, a second point, a
  * space, an exponent) are not typed.
  *
  * The arithmetic is decimal (`BigDecimal`), so a step of 0.1 from 0.2 gives
  * 0.3, not 0.30000000000000004. A number shows as kyo-ui's `NumberFormat`
  * shows it: no point for a whole number ("3"), else the decimals ("2.5").
  */
// claude 2026-10-19: created (L1c): the number field and the range
private[tui4s] object NumberText:

    /** True when `s` can be the text of a number field while the user types:
      * the grammar of the object, with no digit needed.
      */
    // claude 2026-10-19: changed: no plus sign, as in HTML (L1c review)
    def partial(s: String): Boolean =
        val body  = s.stripPrefix("-")
        val parts = body.split("\\.", -1).toVector
        parts.size <= 2 && parts.forall(_.forall(c => c >= '0' && c <= '9'))

    /** The number of `s`, when `s` is [[partial]] and has a digit. */
    def parse(s: String): Option[BigDecimal] =
      // claude 2026-10-19: changed: no plus sign (L1c review)
      Option.when(partial(s) && s.exists(_.isDigit)) {
        val sign = if s.startsWith("-") then "-" else ""
        val abs  = s.stripPrefix("-")
        // "1." and ".5" are numbers: write them as "1.0" and "0.5"
        BigDecimal(
          sign + (if abs.startsWith(".") then "0" + abs else abs) +
            (if abs.endsWith(".") then "0" else ""),
        )
      }

    /** The text of `v`, as kyo-ui's `NumberFormat` shows a number. */
    def format(v: BigDecimal): String =
        val plain = v.bigDecimal.stripTrailingZeros.nn.toPlainString.nn
        if plain == "-0" then "0" else plain

    /** `v` moved by `steps` steps of `step` (1 when none or not positive), then
      * clamped to `min` and `max` (no bound when none).
      */
    def stepBy(
        v: BigDecimal,
        steps: Int,
        step: Option[Double],
        min: Option[Double],
        max: Option[Double],
    ): BigDecimal =
        val by = BigDecimal.decimal(step.filter(_ > 0).getOrElse(1.0))
        clamp(v + by * steps, min, max)
    end stepBy

    /** `v` on the grid of the steps of `step` (1 when none or not positive)
      * from `min`, inside `min` and `max`, as HTML puts the value of a range on
      * its steps: the nearest value `min + k * step` (the upper one at a tie),
      * at least `min` and at most the largest such value not above `max`. `min`
      * when `max` is below `min`.
      */
    // claude 2026-10-19: created (L1c review): the grid of a range
    def onGrid(
        v: BigDecimal,
        step: Option[Double],
        min: Double,
        max: Double,
    ): BigDecimal =
        val by                               = BigDecimal.decimal(step.filter(_ > 0).getOrElse(1.0))
        val lo                               = BigDecimal.decimal(min)
        val hi                               = BigDecimal.decimal(max)
        def at(k: BigDecimal): BigDecimal    = lo + by * k
        def floor(x: BigDecimal): BigDecimal =
          x.setScale(0, BigDecimal.RoundingMode.FLOOR)
        if hi < lo then lo
        else
            at(floor((v - lo) / by + BigDecimal("0.5")))
              .max(lo)
              .min(at(floor((hi - lo) / by)))
    end onGrid

    /** `v` clamped to `min` and `max` (no bound when none). */
    def clamp(
        v: BigDecimal,
        min: Option[Double],
        max: Option[Double],
    ): BigDecimal =
        val low = min.fold(v)(m => v.max(BigDecimal.decimal(m)))
        max.fold(low)(m => low.min(BigDecimal.decimal(m)))

    /** The text of a number field after ArrowUp (`steps` 1) or ArrowDown
      * (`steps` -1): its number (0 when the text is not a number) moved by
      * `step` and clamped to `min` and `max`, as the browser's `stepUp` and
      * `stepDown` do. Gives the new text and its number.
      */
    def step(
        text: String,
        steps: Int,
        step: Option[Double],
        min: Option[Double],
        max: Option[Double],
    ): (String, BigDecimal) =
        val v =
          stepBy(parse(text).getOrElse(BigDecimal(0)), steps, step, min, max)
        (format(v), v)
    end step
end NumberText
