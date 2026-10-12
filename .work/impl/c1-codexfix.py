#!/usr/bin/env python3
"""Applies the PR #10 review fixes (Codex findings) one at a time.
usage: codexfix.py <ROOT> <F1|F2|F3|F4|F5|F6|all> <tests|code|both>
Each replacement names an exact anchor; the script stops when an anchor is
missing or not unique, so a fix is never applied twice.
claude 2026-10-12: created
"""
import pathlib, sys

ROOT = pathlib.Path(sys.argv[1])
WHICH = sys.argv[2]
PART = sys.argv[3]
MAIN = ROOT / 'modules/core/src/main/scala/tui4s/internal'
TEST = ROOT / 'modules/core/src/test/scala/tui4s'
MARK = 'claude 2026-10-12'

def rep(path, old, new):
    t = path.read_text()
    n = t.count(old)
    if n != 1:
        sys.exit(f'{path.name}: anchor found {n} times, expected 1:\n{old[:120]!r}')
    path.write_text(t.replace(old, new, 1))
    print(f'  {path.name}: ok')

# ---------------------------------------------------------------- F1 wrap
def f1_code():
    rep(MAIN / 'Layout.scala',
        "                case _                                                        => acc ++ word.grouped(w).toVector\n",
        "                case _                                                        => acc ++ chunksByWidth(word, w)\n")
    rep(MAIN / 'Layout.scala',
        "    def textLines(text: String, width: Int, wrapped: Boolean): Vector[String] =\n",
        f"""    /** Splits a word into pieces of at most `w` cells. The split is at a code
      * point border: a wide character or a surrogate pair does not split.
      */
    // {MARK}: created (PR #10 review: Codex, Layout.scala:114)
    private def chunksByWidth(word: String, w: Int): Vector[String] =
        val glyphs = Vector.unfold(0) {{ i =>
          if i >= word.length then None
          else
              val cp = word.codePointAt(i)
              Some((String(Character.toChars(cp)), i + Character.charCount(cp)))
        }}
        glyphs.foldLeft(Vector.empty[String]) {{ (acc, g) =>
          acc.lastOption match
              case Some(last) if textWidth(last) + textWidth(g) <= w =>
                acc.init :+ (last + g)
              case _                                                 => acc :+ g
        }}
    end chunksByWidth

    def textLines(text: String, width: Int, wrapped: Boolean): Vector[String] =
""")

def f1_tests():
    rep(TEST / 'GoldenScreenTest.scala',
        """      "a long word splits" in {
        assertScreen(div("abcdefgh"), 5, 2, "abcde\\nfgh")
      }
""",
        f"""      "a long word splits" in {{
        assertScreen(div("abcdefgh"), 5, 2, "abcde\\nfgh")
      }}
      // {MARK}: created (PR #10 review: Codex, Layout.scala:114)
      "a long word of wide characters splits by cells, not by characters" in {{
        assertScreen(div("漢字"), 2, 2, "漢\\n字")
      }}
""")

# ---------------------------------------------------------------- F2 shrink
def f2_code():
    rep(MAIN / 'Layout.scala',
        """                if total <= 0 then base
                else
                    val cuts =
                      weights.map(w => math.ceil(-free * w / total).toInt)
                    base.zip(cuts).zip(floor).map { case ((b, c), f) =>
                      (b - c).max(f).max(0)
                    }
""",
        f"""                if total <= 0 then base
                else
                    // {MARK}: changed (PR #10 review: Codex, Layout.scala:399):
                    // the cuts add up to the deficit, not more. Each item
                    // loses the whole part of its share; the rest goes one
                    // cell at a time to the items with the largest fraction
                    // (the last item first when equal), never below the floor.
                    val deficit = -free
                    val exact   = weights.map(w => deficit * w / total)
                    val whole   = exact.map(_.toInt)
                    val room    = base.zip(whole).zip(floor).map {{
                      case ((b, c), f) => (b - c - f).max(0)
                    }}
                    val order   = exact
                      .zip(whole)
                      .zipWithIndex
                      .map {{ case ((e, c), i) => (e - c, i) }}
                      .sortBy {{ case (frac, i) => (-frac, -i) }}
                      .map(_._2)
                    val extra   = order
                      .foldLeft((Vector.fill(items.size)(0), deficit - whole.sum)) {{
                        case ((acc, left), i) =>
                          if left > 0 && room(i) > 0 then
                              (acc.updated(i, 1), left - 1)
                          else (acc, left)
                      }}
                      ._1
                    base.zip(whole.zip(extra)).zip(floor).map {{
                      case ((b, (c, x)), f) => (b - c - x).max(f).max(0)
                    }}
""")

def f2_tests():
    rep(TEST / 'GoldenScreenTest.scala',
        """      "flexGrow takes the free rows of a column" in {
        val ui =
          div.style(_.height(3.px))(span("a").style(_.flexGrow(1)), span("b"))
        assertScreen(ui, 3, 3, "a\\n\\nb")
      }
""",
        f"""      "flexGrow takes the free rows of a column" in {{
        val ui =
          div.style(_.height(3.px))(span("a").style(_.flexGrow(1)), span("b"))
        assertScreen(ui, 3, 3, "a\\n\\nb")
      }}
      // {MARK}: created (PR #10 review: Codex, Layout.scala:399)
      "flexShrink removes only the deficit; the last item loses the cell" in {{
        // three one-cell items in two cells: one cell too many, not three
        assertScreen(
          div.style(_.row)(span("a"), span("b"), span("c")),
          2,
          1,
          "ab",
        )
        assertScreen(div.style(_.row)(span("abc"), span("def")), 5, 1, "abcde")
      }}
""")

# ---------------------------------------------------------------- F3 table relayout
def f3_code():
    rep(MAIN / 'Layout.scala',
        """                        o.props == m.props && tableCols == ctx.cols,
""",
        f"""                        // {MARK}: changed (PR #10 review: Codex,
                        // Layout.scala:703): the OLD node's columns against
                        // the new ones (`ctx.cols` was built from `tableCols`)
                        o.props == m.props && o.cols == tableCols,
""")

def f3_tests():
    p = TEST / 'RelayoutTest.scala'
    if p.exists():
        sys.exit('RelayoutTest.scala exists')
    p.write_text(f'''package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.internal.*

/** The incremental re-layout of one container (`Layout.relayout`): the path
  * that the runner takes when a region changes.
  */
// {MARK}: created (PR #10 review: Codex, Layout.scala:703)
class RelayoutTest extends Test[Any]:

    "a retained table row follows the new column widths" in {{
        // row 2's first cell is a region; row 1 does not change
        val sig            = Signal.initConst("a")
        val ui             = table(tr(td("x"), td("y")), tr(td(sig), td("z")))
        def env(first: String): Scene =
          Scene(
            _ => Some(Content.Single(UI.Ast.Text(first))),
            None,
            Size(20, 4),
            _ => 0,
          )
        val laid1          = Layout.layoutAll(ui, env("a"))
        val region         = laid1.regionOwner.keys.head
        val owner          = laid1.regionOwner(region)
        // the region's text grows: the first column gets wider
        val (laid2, _)     =
          Layout.relayout(laid1, owner, Set.empty, env("aaaaaaaa"), ui)
        val secondCells    = laid2.nodes.values.filter(n =>
          n.index == 1 && (n.kind match
              case Kind.El(_: UI.Ast.Td) => true
              case _                     => false),
        ).toVector
        assert(secondCells.size == 2, s"${{secondCells.size}} second cells")
        assert(
          secondCells.map(_.rect.x).distinct.size == 1,
          "the second cells are not aligned: " + secondCells
            .map(n => n.path.showLoc + " at x=" + n.rect.x)
            .mkString(", "),
        )
    }}
end RelayoutTest
''')
    print('  RelayoutTest.scala: created')

# ---------------------------------------------------------------- F4 reverse
def f4_code():
    rep(MAIN / 'Props.scala',
        """final case class Props(
    dir: Dir,
    gap: Int,
""",
        f"""final case class Props(
    dir: Dir,
    /** `rowReverse` or `columnReverse`: the items go from the end. */
    reverse: Boolean, // {MARK}: created (PR #10 review: Codex, Props.scala:303)
    gap: Int,
""")
    rep(MAIN / 'Props.scala',
        """      Props(
        Dir.Column,
        0,
        Sides.zero,
""",
        f"""      Props(
        Dir.Column,
        false, // {MARK}: created (reverse)
        0,
        Sides.zero,
""")
    rep(MAIN / 'Props.scala',
        """            case Prop.FlexDirectionProp(d)         =>
              p.copy(dir = d match
                  case FlexDirection.row | FlexDirection.rowReverse       => Dir.Row
                  case FlexDirection.column | FlexDirection.columnReverse =>
                    Dir.Column)
""",
        f"""            case Prop.FlexDirectionProp(d)         =>
              // {MARK}: changed (PR #10 review: Codex, Props.scala:303): the
              // reverse variants keep their flag
              d match
                  case FlexDirection.row           =>
                    p.copy(dir = Dir.Row, reverse = false)
                  case FlexDirection.rowReverse    =>
                    p.copy(dir = Dir.Row, reverse = true)
                  case FlexDirection.column        =>
                    p.copy(dir = Dir.Column, reverse = false)
                  case FlexDirection.columnReverse =>
                    p.copy(dir = Dir.Column, reverse = true)
""")
    rep(MAIN / 'Layout.scala',
        """    def arrange(
        p: Props,
        box: Rect,
        items: Vector[Measured],
        cols: Vector[Int],
    ): Vector[Rect] =
      if items.isEmpty then Vector.empty
      else if cols.nonEmpty then
          // a table row: fixed column widths
          items.zipWithIndex.map { (m, j) =>
""",
        f"""    def arrange(
        p0: Props,
        box: Rect,
        items0: Vector[Measured],
        cols: Vector[Int],
    ): Vector[Rect] =
      if items0.isEmpty then Vector.empty
      else if cols.nonEmpty then
          // a table row: fixed column widths
          items0.zipWithIndex.map {{ (m, j) =>
""")
    rep(MAIN / 'Layout.scala',
        """      else
          val column                   = p.dir == Dir.Column
          def mainOf(s: Size): Int     = if column then s.h else s.w
""",
        f"""      else
          // {MARK}: changed (PR #10 review: Codex, Props.scala:303): a
          // reversed container (`rowReverse`, `columnReverse`) places its
          // items in the opposite order, with the main start at the far end
          // (so `justify: start` packs them there, as in CSS). The rects
          // come back in the items' own order.
          val items                    = if p0.reverse then items0.reverse else items0
          val p                        =
            if !p0.reverse then p0
            else
                p0.copy(justify = p0.justify match
                    case Justification.start => Justification.end
                    case Justification.end   => Justification.start
                    case j                   => j)
          val column                   = p.dir == Dir.Column
          def mainOf(s: Size): Int     = if column then s.h else s.w
""")
    rep(MAIN / 'Layout.scala',
        """          items.zip(sizes).zip(offsets).map { case ((m, s), off) =>
            val mainPos       =
""",
        """          val rects                    = items.zip(sizes).zip(offsets).map { case ((m, s), off) =>
            val mainPos       =
""")
    rep(MAIN / 'Layout.scala',
        """            if column then Rect(box.x + crossPos, box.y + mainPos, crossLen, s)
            else Rect(box.x + mainPos, box.y + crossPos, s, crossLen)
          }
    end arrange
""",
        """            if column then Rect(box.x + crossPos, box.y + mainPos, crossLen, s)
            else Rect(box.x + mainPos, box.y + crossPos, s, crossLen)
          }
          if p0.reverse then rects.reverse else rects
    end arrange
""")

def f4_tests():
    rep(TEST / 'GoldenScreenTest.scala',
        """        assertScreen(div.style(_.row)(span("abc"), span("def")), 5, 1, "abcde")
      }
""",
        f"""        assertScreen(div.style(_.row)(span("abc"), span("def")), 5, 1, "abcde")
      }}
      // {MARK}: created (PR #10 review: Codex, Props.scala:303)
      "rowReverse and columnReverse place the items from the end" in {{
        assertScreen(div.style(_.rowReverse)(span("a"), span("b")), 4, 1, "  ba")
        assertScreen(
          div.style(_.columnReverse.height(3.px))(span("a"), span("b")),
          1,
          3,
          "\\nb\\na",
        )
      }}
""")

# ---------------------------------------------------------------- F5 percent heights
def f5_code():
    L = MAIN / 'Layout.scala'
    rep(L,
        """    def measureItem(
        item: Item,
        availW: Int,
        parent: Props,
""",
        """    def measureItem(
        item: Item,
        availW: Int,
        availH: Option[Int],
        parent: Props,
""")
    rep(L,
        """            Measured(
              item,
              p,
              measureElement(e, item.path, p, availW - p.margin.h, env),
            )
""",
        """            Measured(
              item,
              p,
              measureElement(
                e,
                item.path,
                p,
                availW - p.margin.h,
                availH.map(h => (h - p.margin.v).max(0)),
                env,
              ),
            )
""")
    rep(L,
        """    def measureElement(
        e: Element,
        path: Loc,
        p: Props,
        availW: Int,
        env: Scene,
    ): Size =
        val ins           = p.insets
        val fixedW        = p.width.flatMap(Props.cells(_, availW, env.screen.h))
        val inner         = (fixedW.getOrElse(availW) - ins.h).max(0)
        val content: Size = intrinsic(e).getOrElse {
          e match
              case _: Table => tableSize(e, path, p, inner, env)._2
              case _        =>
                val flat     = itemsOf(e, path, env)
                val measured = flat.items.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, p, env, p.listStyle, i),
                )
                combine(p, measured)
        }
        val w             = fixedW.getOrElse(content.w + ins.h)
        val h             = p.height
          .flatMap(Props.cells(_, env.screen.h, env.screen.h))
          .getOrElse(content.h + ins.v)
        Size(
          clamp(
            w,
            p.minW.flatMap(Props.cells(_, availW, env.screen.h)),
            p.maxW.flatMap(Props.cells(_, availW, env.screen.h)),
          ),
          clamp(
            h,
            p.minH.flatMap(Props.cells(_, env.screen.h, env.screen.h)),
            p.maxH.flatMap(Props.cells(_, env.screen.h, env.screen.h)),
          ),
        )
    end measureElement
""",
        f"""    /** @param availH
      *   the height of the containing block when it is definite: a percentage
      *   height is relative to it. `None` makes a percentage height `auto`, as
      *   in CSS.
      */
    def measureElement(
        e: Element,
        path: Loc,
        p: Props,
        availW: Int,
        availH: Option[Int],
        env: Scene,
    ): Size =
        val ins           = p.insets
        val fixedW        = p.width.flatMap(Props.cells(_, availW, env.screen.h))
        // {MARK}: changed (PR #10 review: Codex, Layout.scala:264): a
        // percentage height was relative to the screen
        def cellsH(l: Length): Option[Int] = l match
            case Length.Pct(v) => availH.map(h => (v * h / 100).toInt)
            case _             => Props.cells(l, 0, env.screen.h)
        val fixedH        = p.height.flatMap(cellsH)
        val inner         = (fixedW.getOrElse(availW) - ins.h).max(0)
        val innerH        = fixedH.map(h => (h - ins.v).max(0))
        val content: Size = intrinsic(e).getOrElse {{
          e match
              case _: Table => tableSize(e, path, p, inner, env)._2
              case _        =>
                val flat     = itemsOf(e, path, env)
                val measured = flat.items.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, innerH, p, env, p.listStyle, i),
                )
                combine(p, measured)
        }}
        val w             = fixedW.getOrElse(content.w + ins.h)
        val h             = fixedH.getOrElse(content.h + ins.v)
        Size(
          clamp(
            w,
            p.minW.flatMap(Props.cells(_, availW, env.screen.h)),
            p.maxW.flatMap(Props.cells(_, availW, env.screen.h)),
          ),
          clamp(h, p.minH.flatMap(cellsH), p.maxH.flatMap(cellsH)),
        )
    end measureElement
""")
    rep(L,
        """          ).items.zipWithIndex.map((c, i) =>
            measureItem(c, inner, rp, env, None, i).size.w,
          )
""",
        """          ).items.zipWithIndex.map((c, i) =>
            measureItem(c, inner, None, rp, env, None, i).size.w,
          )
""")
    rep(L,
        """        val inner = node.content.w
        node.kind match
            case Kind.Screen       =>
              val flat = flatten(root, Loc.root, Nil, env)
              (
                flat.items.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, node.props, env, None, i),
                ),
""",
        f"""        val inner = node.content.w
        // {MARK}: created (PR #10 review: Codex, Layout.scala:264): the height
        // that the kids' percentages resolve against: the content height when
        // this node's height is definite (the screen, an explicit height, or
        // a size that the flex parent imposed), else none
        val availH: Option[Int] = node.kind match
            case Kind.Screen => Some(node.content.h)
            case _ if node.props.height.isDefined || node.rect.h != node.natural.h =>
              Some(node.content.h)
            case _ => None
        node.kind match
            case Kind.Screen       =>
              val flat = flatten(root, Loc.root, Nil, env)
              (
                flat.items.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, availH, node.props, env, None, i),
                ),
""")
    rep(L,
        """              (
                rows.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, node.props, env, None, i),
                ),
""",
        """              (
                rows.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, availH, node.props, env, None, i),
                ),
""")
    rep(L,
        """                flat.items.zipWithIndex.map((it, i) =>
                  measureItem(
                    it,
                    inner,
                    node.props,
                    env,
                    node.props.listStyle,
                    i,
                  ),
                ),
""",
        """                flat.items.zipWithIndex.map((it, i) =>
                  measureItem(
                    it,
                    inner,
                    availH,
                    node.props,
                    env,
                    node.props.listStyle,
                    i,
                  ),
                ),
""")
    rep(L,
        """    // claude 2026-10-12: changed: the unused parameter `kids` is removed
    private def naturalOf(node: Node, env: Scene): Size =
      node.kind match
          case Kind.Screen    => node.natural
          case Kind.El(e)     =>
            measureElement(
              e,
              node.path,
              node.props,
              node.availW - node.props.margin.h,
              env,
            )
          case Kind.Txt(_, _) => node.natural
""",
        f"""    // claude 2026-10-12: changed: the unused parameter `kids` is removed;
    // changed (PR #10 review: Codex, Layout.scala:264): `availH` added
    private def naturalOf(node: Node, availH: Option[Int], env: Scene): Size =
      node.kind match
          case Kind.Screen    => node.natural
          case Kind.El(e)     =>
            measureElement(
              e,
              node.path,
              node.props,
              node.availW - node.props.margin.h,
              availH.map(h => (h - node.props.margin.v).max(0)),
              env,
            )
          case Kind.Txt(_, _) => node.natural
""")
    rep(L,
        """              val propsChanged       = node.props != node0.props
              val newNatural         = naturalOf(updatedNode, env)
""",
        f"""              val propsChanged       = node.props != node0.props
              // {MARK}: changed (PR #10 review: Codex, Layout.scala:264): the
              // owner's content height, as `measuredKids` gives it
              val ownerH             =
                node.owner.flatMap(laid3.nodes.get).map(_.content.h)
              val newNatural         = naturalOf(updatedNode, ownerH, env)
""")

def f5_tests():
    rep(TEST / 'GoldenScreenTest.scala',
        """    "decorations" - {
""",
        f"""    // {MARK}: created (PR #10 review: Codex, Layout.scala:264)
    "percentage sizes" - {{
      "a percentage height is relative to the parent, not to the screen" in {{
        val ui = div.style(_.height(4.px))(
          div("x").style(_.height(50.pct).bg(_.red)),
        )
        val b  = buffer(ui, 4, 20)
        assert(b.cellAt(0, 1).bg == JColor.Rgb(239, 68, 68))
        assert(b.cellAt(0, 2).bg != JColor.Rgb(239, 68, 68))
      }}
    }}

    "decorations" - {{
""")

# ---------------------------------------------------------------- F6 range input
def f6_code():
    rep(MAIN / 'Paint.scala',
        """            case ri: RangeInput =>
              val v   = boundValue(env, n.path)
                .collect { case d: Double => d }
                .getOrElse(0.0)
""",
        f"""            case ri: RangeInput =>
              // {MARK}: changed (PR #10 review: Codex, Paint.scala:393): a
              // constant value (`.value(50.0)`) comes from the element, as in
              // the text and picker paths; only a bound ref comes from the
              // scene
              val v   = (ri.value match
                  case Present(UI.Bound.Const(c)) => Some(c)
                  case _                          =>
                    boundValue(env, n.path).collect {{ case d: Double => d }})
                .getOrElse(0.0)
""")

def f6_tests():
    rep(TEST / 'GoldenScreenTest.scala',
        """    "decorations" - {
""",
        f"""    // {MARK}: created (PR #10 review: Codex, Paint.scala:393)
    "inputs" - {{
      "a range input shows its constant value" in {{
        assertScreen(
          div.style(_.row)(rangeInput("r").value(50.0)),
          12,
          1,
          "─────●──────",
        )
      }}
    }}

    "decorations" - {{
""")

FIXES = {
    'F1': (f1_tests, f1_code), 'F2': (f2_tests, f2_code), 'F3': (f3_tests, f3_code),
    'F4': (f4_tests, f4_code), 'F5': (f5_tests, f5_code), 'F6': (f6_tests, f6_code),
}
names = list(FIXES) if WHICH == 'all' else [WHICH]
for n in names:
    t, c = FIXES[n]
    if PART in ('tests', 'both'):
        print(f'{n} tests'); t()
    if PART in ('code', 'both'):
        print(f'{n} code'); c()
