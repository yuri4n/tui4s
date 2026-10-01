package tui4s

/** Background work that folds values into the state.
  *
  * A source is used in two ways: declared by `sources(state)` (standing work,
  * compared by label after each change), or started once by
  * `state.withTask(...)` (a one-shot task).
  *
  * Identity: the call site where the source was built, or `.id(name)`. Two
  * sources are equal when their labels are equal.
  *
  * @param label
  *   the identity shown in tests and traces: "DashboardApp.scala:41", or the id
  */
final class Source[S] private[tui4s] (
    val label: String,
    private[tui4s] val recoverWith: ((S, Throwable) => Next[S]) | Null,
    private[tui4s] val keyed: Boolean,
):
    /** The same work with the identity `name`. A new id is a new source: the
      * runtime stops the old one. Put changing parameters in the id. For a
      * task: a task with an id interrupts the running task with the same id
      * (the latest wins).
      */
    def id(name: String): Source[S] =
      new Source(name, recoverWith, keyed = true)

    /** The same work; a failure folds into the state with `f` instead of ending
      * the app.
      */
    def recover(f: (S, Throwable) => Next[S]): Source[S] =
      new Source(label, f, keyed)

    override def equals(that: Any): Boolean = that match
        case s: Source[?] => s.label == label
        case _            => false

    override def hashCode: Int = label.hashCode

    override def toString: String = s"Source($label)"
end Source
