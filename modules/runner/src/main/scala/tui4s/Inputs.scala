package tui4s

import kyo.UI.Ast.TextInput

/** The data attribute of [[maxLength]]: the runner reads it. */
// claude 2026-10-19: created (L1c)
private[tui4s] val MaxLengthAttr: String = "tui4s-maxlength"

/** The most grapheme clusters (what a reader sees as one character) that a text
  * input or a textarea takes from the keys and from a paste.
  *
  * {{{
  * import kyo.*
  * import kyo.UI.*
  * import tui4s.*
  *
  * input.value(name).maxLength(20)
  * textarea.value(message).maxLength(500)
  * }}}
  *
  * kyo-ui 1.0.0-RC7 has no `maxLength` attribute: this extension sets the data
  * attribute `tui4s-maxlength`, and the runner reads it. A key that would pass
  * the limit does nothing; a paste is cut to the clusters that fit, so no
  * character is split. A value that the app writes to the bound ref is not cut.
  * A negative limit is 0. kyo-ui's DOM runner writes the attribute as
  * `data-tui4s-maxlength`, and a browser does not read it.
  */
// claude 2026-10-19: created (L1c): the missing attribute of kyo-ui
extension (e: TextInput)
    def maxLength(n: Int): e.Self = e.data(MaxLengthAttr, n.max(0).toString)
