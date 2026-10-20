package tui4s

import tastyquery.Classpaths.ClasspathEntry
import tastyquery.Constants.Constant
import tastyquery.Contexts.Context
import tastyquery.Modifiers.Visibility
import tastyquery.Names.*
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Types.*
import tastyquery.jdk.ClasspathLoaders
import tui4s.internal.discard

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileSystems, Path}
import java.security.MessageDigest
import scala.io.Source

/** The kyo-ui surface that the runner depends on, read from the TASTy of the
  * kyo-ui jar on the test class path (RC-12): the types of `kyo.UI.Ast` and the
  * cases of `kyo.Style.Prop` (C1); the fields of `UI.Ast.Reactive` and
  * `UI.Ast.Foreach`, the cases of `UI.Bound`, and the two `kyo.Signal` methods
  * of the watcher loop, read from the kyo-core TASTy (C2); what `Dispatch`
  * reads (the `Attrs` fields, the members of the input elements, the event
  * types and the cases of `Keyboard`), the pin that `Foreach.applyTyped` is
  * still private, and the shape hashes of the kyo-ui rules that `Dispatch` and
  * `Mount` copy (C3). One line per item, `kind<TAB>name<TAB>detail`, the same
  * format as the spike S3 guard.
  */
// claude 2026-10-12: created (from the guard of spike S3: ADR 0003, ADR 0004);
// changed: the C2 part (ast.fields, bound.case, signal.method); 2026-10-20:
// changed: the ADRs, not a path of the working notes
// claude 2026-10-13: changed: the C3 part (attrs.field, ui.member, event.fields,
// keyboard.case, pin, rule, js)
object KyoUiSurface:

    /** The rules of kyo-ui that the runner copies (K6, K7): the methods of
      * `kyo.internal.ReactiveUI` and the fragments of the client JS (keydown,
      * click, focus), the same lists as the spike S3 guard.
      */
    // claude 2026-10-13: created (C3, from the S3 guard's RuleNames)
    object RuleNames:
        val reactiveUi: Set[String] = Set(
          "dispatchToElement",
          "dispatch",
          "keepBubbling",
          "isDisabled",
          "isReadOnly",
          "isHidden",
          "readChecked",
          "toggleChecked",
          "cycleSelectOption",
          "targetSatisfies",
          "isTargetDisabled",
          "isTargetButton",
          "isTargetSelect",
          "normalize",
          "normalizeAt",
          "collectSignalRef",
          "walkStatic",
          "subscribeScoped",
          "subscribe",
          "findAllFocusableIds",
        )

        /** (label, start regex, end regex) on the normalized client JS. */
        val jsFragments: List[(String, String, String)] = List(
          (
            "keydown rules",
            """else if\(t==="keydown"\)\{""",
            """else if\(t==="keyup"""",
          ),
          (
            "click rules",
            """^\s*if\(t==="click"\)""",
            """else if\(t==="input"""",
          ),
          (
            "focusAuto seeding",
            """^\s*function seedFocusAuto""",
            """^\s*// Unwind""",
          ),
          (
            "sweepFocusAuto (restore)",
            """^\s*function sweepFocusAuto""",
            """^\s*// Initial mount""",
          ),
        )

        /** The cases of `kyo.internal.CssStyleRenderer.renderProp` whose CSS
          * the runner copies or matches: `PositionProp` (the boxes of `overlay`
          * and `dropdown` in `Props.position`) and `DisplayProp` (the values of
          * `Warnings`).
          */
        // claude 2026-10-19: created (L1b review)
        val cssCases: List[String] = List("PositionProp", "DisplayProp")

        /** The kyo-ui enums whose cases the runner matches: `Style.Position`,
          * `Style.Display` and `Style.FlexWrap` (`Props`, `Layout`,
          * `Warnings`), and `Stylesheet.Entry` (`CssVars`).
          */
        // claude 2026-10-19: created (L1b review)
        val valueEnums: List[String] = List(
          "kyo.Style.Position",
          "kyo.Style.Display",
          "kyo.Style.FlexWrap",
          "kyo.Stylesheet.Entry",
        )
    end RuleNames

    /** The fields of `UI.Ast.Attrs` that `Dispatch` (and the focus order of
      * `Layout`) read through the public `Element.attrs`.
      */
    // claude 2026-10-13: created (C3)
    val attrsFields: Set[String] = Set(
      "identifier",
      "tabIndex",
      "focusTrap",
      "focusGroup",
      "focusAuto",
      // claude 2026-10-19: created (L1b): the focus seeds; the class of a
      // selector of Stylesheet.scopedVars (CssVars)
      "focusRestore",
      "cssClasses",
      "stopPropagation",
      "onClick",
      "onClickEvt",
      "onClickSelf",
      "onClickSelfEvt",
      "onKeyDown",
      "onFocus",
      "onFocusEvt",
      "onBlur",
      "onBlurEvt",
      "ariaAttrs",
      "dataAttrs",
    )
    // claude 2026-10-13: changed (PR #13 review: Codex, Dispatch.scala:401,
    // :204): the event forms of onFocus, onBlur and Form.onSubmit
    // claude 2026-10-19: changed (L1a): ariaAttrs (the scroll target of
    // `aria-activedescendant`) and dataAttrs (kyo-ui's `preventScrollKeys`)

    /** The members of the elements that `Dispatch` reads, by owner. */
    // claude 2026-10-13: created (C3)
    val elementMembers: Map[String, Set[String]] = Map(
      "kyo.UI.Ast.Element"      -> Set("attrs", "children"),
      "kyo.UI.Ast.HasDisabled"  -> Set("disabled"),
      "kyo.UI.Ast.TextInput"    -> Set("value", "readOnly", "onInput", "onChange"),
      "kyo.UI.Ast.BooleanInput" -> Set("checked", "onChange"),
      "kyo.UI.Ast.Select"       -> Set("value", "onChange", "children"),
      "kyo.UI.Ast.Opt"          -> Set("value"),
      "kyo.UI.Ast.Form"         -> Set("onSubmit", "onSubmitEvt"),
      // claude 2026-10-19: created (L1c): the number field, the range and
      // the dropdown
      "kyo.UI.Ast.NumberInput"  -> Set("min", "max", "step", "onChangeNumeric"),
      "kyo.UI.Ast.RangeInput"   -> Set("value", "min", "max", "step", "onChange"),
      "kyo.UI.Ast.Dropdown"     -> Set("options", "value", "onChange"),
      // claude 2026-10-19: created (L1b review): what CssVars reads
      "kyo.Stylesheet"          -> Set("entries"),
      "kyo.Selector"            -> Set("css"),
    )

    /** One surface line. The key is `(kind, name)`; `detail` is what may
      * change.
      */
    final case class Item(kind: String, name: String, detail: String):
        def key: (String, String) = (kind, name)
        def line: String          = s"$kind\t$name\t$detail"

    /** The checked-in list `kyo-ui-surface.txt` (test resources). */
    def checkedIn: List[Item] =
        val is = getClass.getResourceAsStream("/kyo-ui-surface.txt")
        require(is != null, "test resource kyo-ui-surface.txt is missing")
        try parse(Source.fromInputStream(is, "UTF-8").getLines().toList)
        finally is.close()

    /** The lines of a list: `#` comments and blank lines are skipped. */
    def parse(lines: List[String]): List[Item] =
      lines.filter(l => l.nonEmpty && !l.startsWith("#")).map { l =>
        val c = l.split("\t", -1)
        Item(c(0), c(1), if c.length > 2 then c(2) else "")
      }

    /** The surface of the kyo-ui jar on this JVM's class path. */
    def current: List[Item] =
        val cp  = java.lang.System
          .getProperty("java.class.path", "")
          .split(File.pathSeparator)
          .toList
          .filter(_.nonEmpty)
          .map(Path.of(_))
        val jrt = FileSystems
          .getFileSystem(URI.create("jrt:/"))
          .getPath("modules", "java.base")
        new Reader(jrt :: cp).all
    end current

    /** A compare of `now` with `base`: the lines to report, empty when equal. */
    def compare(now: List[Item], base: List[Item]): List[String] =
        val b       = base.map(i => i.key -> i).toMap
        val n       = now.map(i => i.key -> i).toMap
        val added   =
          now.filterNot(i => b.contains(i.key)).map(i => s"ADDED\t${i.line}")
        val removed =
          base.filterNot(i => n.contains(i.key)).map(i => s"REMOVED\t${i.line}")
        val changed = now
          .filter(i => b.get(i.key).exists(_.detail != i.detail))
          .map(i => s"CHANGED\t${i.line}\twas: ${b(i.key).detail}")
        added ++ removed ++ changed
    end compare

    /** Reads the surface from the TASTy on a class path. */
    final class Reader(cp: List[Path]):
        private val entries = ClasspathLoaders.read(cp)
        given ctx: Context  = Context.initialize(entries)

        private val kyoUi: ClasspathEntry = cp
          .zip(entries)
          .collectFirst {
            case (p, e) if p.getFileName.toString.startsWith("kyo-ui") => e
          }
          .getOrElse(sys.error("no kyo-ui jar on the class path"))

        /** All classes of the kyo-ui jar, nested ones too. */
        private lazy val allClasses: List[ClassSymbol] =
            def nested(c: ClassSymbol): List[ClassSymbol] =
              c :: c.declarations
                .collect { case n: ClassSymbol => n }
                .flatMap(nested)
            ctx
              .findSymbolsByClasspathEntry(kyoUi)
              .toList
              .collect { case c: ClassSymbol => c }
              .flatMap(nested)
              .distinct
        end allClasses

        /** A stable, short name: `kyo.` dropped, module-class `$` dropped. */
        private def full(s: Symbol): String =
          s.displayFullName.stripPrefix("kyo.").replace("$", "")

        private def vis(s: TermOrTypeSymbol): String = s.visibility match
            case Visibility.Public              => "public"
            case Visibility.Private             => "private"
            case Visibility.PrivateThis         => "private[this]"
            case Visibility.ScopedPrivate(sc)   => s"private[${sc.name}]"
            case Visibility.Protected           => "protected"
            case Visibility.ProtectedThis       => "protected[this]"
            case Visibility.ScopedProtected(sc) => s"protected[${sc.name}]"

        /** A name without the counters of unique names (`x$1` becomes `x$#`). */
        private def nm(n: Name): String = n match
            case UniqueName(u, sep, _)     => nm(u) + sep + "#"
            case UniqueTypeName(b, sep, _) => nm(b) + sep + "#"
            case SignedName(u, sig, _)     =>
              nm(u) + "{" + sig.toString.replaceAll("\\$\\d+", "\\$#") + "}"
            case DefaultGetterName(u, n)   => nm(u) + "$default$" + (n + 1)
            case other                     => other.toString.replaceAll("\\$\\d+", "\\$#")

        /** A shallow type text: names only, no prefixes, no symbol resolution. */
        private def tpe(t: Any): String = t match
            case t: TypeRef         => nm(t.name)
            case t: TermRef         => nm(t.name) + ".type"
            case t: AppliedType     =>
              tpe(t.tycon) + t.args.map(tpe).mkString("[", ",", "]")
            case t: ConstantType    => "'" + t.value.value.toString
            case t: ByNameType      => "=>" + tpe(t.resultType)
            case t: RepeatedType    => tpe(t.elemType) + "*"
            case t: AnnotatedType   => tpe(t.typ)
            case t: AndType         => tpe(t.first) + "&" + tpe(t.second)
            case t: OrType          => tpe(t.first) + "|" + tpe(t.second)
            case t: FlexibleType    => tpe(t.nonNullableType)
            case _: WildcardTypeArg => "?"
            case t: TypeParamRef    => nm(t.paramName)
            case t: TermParamRef    => nm(t.paramName) + ".type"
            // claude 2026-10-13: created the three cases below (C3, as in the
            // S3 guard): the sort of overloads in a rule hash uses them
            case t: MethodType      =>
              t.paramNames
                .zip(t.paramTypes)
                .map((n, p) => s"${nm(n)}:${tpe(p)}")
                .mkString("(", ",", ")") + tpe(t.resultType)
            case t: PolyType        =>
              t.paramNames.map(nm).mkString("[", ",", "]") + tpe(t.resultType)
            case t: TypeLambda      =>
              t.paramNames.map(nm).mkString("[", ",", "]=>>") + tpe(
                t.resultType,
              )
            case t: ThisType        => tpe(t.tref) + ".this"
            case other              => other.getClass.getSimpleName

        /** Constructor fields of a class, by name. */
        private def fields(c: ClassSymbol): List[String] =
          c.declarations
            .collect {
              case t: TermSymbol if t.isParamAccessor && !t.isMethod => t
            }
            .map(f => s"${nm(f.name)}:${tpe(f.declaredType)}")
            .sorted

        private def shape(c: ClassSymbol): String =
            val kind    =
              if c.isModuleClass then "object"
              else if c.isTrait then "trait"
              else if c.isCaseClass then "case class"
              else if c.isAbstractClass then "abstract class"
              else "class"
            val parents = c.parents
              .map(tpe)
              .filterNot(
                Set("Object", "Product", "Serializable", "Equals", "Any"),
              )
              .mkString(",")
            s"$kind ${vis(c)} extends $parents"
        end shape

        /** The types of `kyo.UI.Ast`; a class and its companion are one item. */
        def astTypeItems: List[Item] =
            val ast = ctx.findStaticModuleClass("kyo.UI.Ast")
            allClasses
              .filter(c =>
                c.owner == ast && !c.isSynthetic && !full(c).endsWith("$"),
              )
              .groupBy(full)
              .values
              .map(cs => cs.find(!_.isModuleClass).getOrElse(cs.head))
              .toList
              .map(c => Item("ast.type", full(c), shape(c)))
        end astTypeItems

        /** The constructor fields of the region nodes that `Mount` and `Runner`
          * match on: `UI.Ast.Reactive` and `UI.Ast.Foreach`.
          */
        // claude 2026-10-12: created (RC-12, C2 part)
        def astFieldItems: List[Item] =
            val ast = ctx.findStaticModuleClass("kyo.UI.Ast")
            allClasses
              .filter(c =>
                c.owner == ast && !c.isModuleClass &&
                  Set("Reactive", "Foreach").contains(c.name.toString),
              )
              .map(c =>
                Item("ast.fields", full(c), fields(c).mkString("(", ",", ")")),
              )
        end astFieldItems

        /** The cases of `kyo.UI.Bound` (`Const`, `Ref`), with their fields:
          * `Mount.boundRef` matches on `UI.Bound.Ref`.
          */
        // claude 2026-10-12: created (RC-12, C2 part)
        def boundCaseItems: List[Item] =
            val bound = ctx.findStaticModuleClass("kyo.UI.Bound")
            bound.declarations
              .collect { case c: ClassSymbol if c.isCaseClass => c }
              .map(c =>
                Item("bound.case", full(c), fields(c).mkString("(", ",", ")")),
              )

        /** A method signature: the parameter lists with their types and the
          * result, shallow.
          */
        private def sig(t: Any): String = t match
            case m: MethodType =>
              m.paramNames
                .zip(m.paramTypes)
                .map((n, p) => s"${nm(n)}:${tpe(p)}")
                .mkString("(", ",", ")") + sig(m.resultType)
            case p: PolyType   =>
              p.paramNames.map(nm).mkString("[", ",", "]") + sig(p.resultType)
            case other         => ":" + tpe(other)

        /** The `kyo.Signal` methods that the watcher loop of `Mount` uses
          * (`currentWith`, `nextWith`), read from the kyo-core jar.
          */
        // claude 2026-10-12: created (RC-12, C2 part; K29)
        def signalMethodItems: List[Item] =
            val signal = ctx.findStaticClass("kyo.Signal")
            signal.declarations
              .collect {
                case t: TermSymbol
                    if t.isMethod &&
                      Set("currentWith", "nextWith").contains(
                        t.name.toString,
                      ) =>
                  Item(
                    "signal.method",
                    s"Signal.${nm(t.name)}",
                    sig(t.declaredType),
                  )
              }
        end signalMethodItems

        /** The cases of the enum `owner`, with the fields of a case class. */
        // claude 2026-10-19: changed (L1b review): any enum, not only
        // `kyo.Style.Prop`
        private def enumCaseItems(owner: String): List[Item] =
            val e = ctx.findStaticClass(owner)
            e.companionClass.toList.flatMap(_.declarations).collect {
              case t: TermSymbol if t.isEnumCase =>
                Item("enum.case", s"${full(e)}.${nm(t.name)}", "-")
              case c: ClassSymbol
                  if c.isCaseClass && c.isEnum && !c.isModuleClass =>
                Item(
                  "enum.case",
                  s"${full(e)}.${nm(c.name)}",
                  fields(c).mkString("(", ",", ")"),
                )
            }
        end enumCaseItems

        /** The cases of `kyo.Style.Prop` and of [[RuleNames.valueEnums]]. */
        // claude 2026-10-19: changed (L1b review): the value enums
        def propCaseItems: List[Item] =
          ("kyo.Style.Prop" :: RuleNames.valueEnums).flatMap(enumCaseItems)

        /** The fields of `UI.Ast.Attrs` that the runner reads, with their types
          * (the type `Attrs` is `private[kyo]`; its fields are public).
          */
        // claude 2026-10-13: created (C3)
        def attrsFieldItems: List[Item] =
            val attrs = ctx.findStaticClass("kyo.UI.Ast.Attrs")
            attrs.declarations.collect {
              case t: TermSymbol
                  if t.isParamAccessor && !t.isMethod &&
                    attrsFields.contains(nm(t.name)) =>
                Item(
                  "attrs.field",
                  s"${full(attrs)}.${nm(t.name)}",
                  tpe(t.declaredType),
                )
            }
        end attrsFieldItems

        /** The members of the elements that `Dispatch` reads: the accessors
          * with no parameter list (a field, or a `def` with no parameters),
          * with their visibility and result type.
          */
        // claude 2026-10-13: created (C3)
        def elementMemberItems: List[Item] =
          elementMembers.toList.flatMap { (owner, names) =>
            val c = ctx.findStaticClass(owner)
            c.declarations.collect {
              case t: TermSymbol
                  if names.contains(nm(t.name)) && !t.isSynthetic &&
                    (t.declaredType match
                        case _: MethodType | _: PolyType => false
                        case _ => true) =>
                Item(
                  "ui.member",
                  s"${full(c)}.${nm(t.name)}",
                  s"${vis(t)} ${tpe(t.declaredType)}",
                )
            }
          }

        /** The fields of the event types that `Dispatch` builds or reads:
          * `KeyboardEvent`, `MouseEvent` and `Modifiers`.
          */
        // claude 2026-10-13: created (C3)
        def eventItems: List[Item] =
          List("kyo.UI.KeyboardEvent", "kyo.UI.MouseEvent", "kyo.UI.Modifiers")
            .map(ctx.findStaticClass)
            .map(c =>
              Item("event.fields", full(c), fields(c).mkString("(", ",", ")")),
            )

        /** The cases of `kyo.UI.Keyboard`, with the fields of a case class. */
        // claude 2026-10-13: created (C3)
        def keyboardCaseItems: List[Item] =
            val kb = ctx.findStaticClass("kyo.UI.Keyboard")
            kb.companionClass.toList.flatMap(_.declarations).collect {
              case t: TermSymbol if t.isEnumCase                       =>
                Item("keyboard.case", s"${full(kb)}.${nm(t.name)}", "-")
              case c: ClassSymbol if c.isCaseClass && !c.isModuleClass =>
                Item(
                  "keyboard.case",
                  s"${full(kb)}.${nm(c.name)}",
                  fields(c).mkString("(", ",", ")"),
                )
            }
        end keyboardCaseItems

        /** K2: `Foreach.applyTyped` is still private, so `Mount` casts a
          * `Foreach[?, ?]` to `Foreach[Any, UI]`. When kyo-ui opens it, this
          * line changes and the cast can go. K3: `kyo.Signal` and
          * `Signal.SignalRef` declare no `equals` and no `hashCode`, so the
          * component registry finds a component node by the identity of its
          * signal.
          */
        // claude 2026-10-13: created (C3, RC-12 part, K2); changed (C4): K3
        def pinItems: List[Item] =
            val fe                          = ctx.findStaticClass("kyo.UI.Ast.Foreach")
            val v                           = fe.declarations
              .collect {
                case t: TermSymbol if nm(t.name) == "applyTyped" => vis(t)
              }
              .distinct
              .sorted
            // claude 2026-10-13: created (C4, K3): the component registry
            // (`internal.ComponentSpec`, a weak map) finds a component node by
            // its signal, so it needs the identity of a `Signal`: no `equals`
            // and no `hashCode` of its own in `kyo.Signal` and
            // `kyo.Signal.SignalRef`. A line that names one of them means that
            // two different signals can find the same entry
            def own(c: ClassSymbol): String =
                val names = c.declarations
                  .collect {
                    case t: TermSymbol
                        if t.isMethod &&
                          Set("equals", "hashCode").contains(t.name.toString) =>
                      t.name.toString
                  }
                  .distinct
                  .sorted
                if names.isEmpty then "none" else names.mkString("|")
            end own
            val identity                    =
              s"Signal: ${own(ctx.findStaticClass("kyo.Signal"))}; " +
                s"SignalRef: ${own(ctx.findStaticClass("kyo.Signal.SignalRef"))}"
            List(
              Item(
                "pin",
                "K2 UI.Ast.Foreach.applyTyped",
                if v.isEmpty then "MISSING" else v.mkString("|"),
              ),
              Item("pin", "K3 Signal equals and hashCode", identity),
            )
        end pinItems

        // ---- the shape hashes of the rules (C3, from the S3 guard) ----

        /** True for a tree that the compiler wrote (see `walk`). */
        private def skipped(t: Tree): Boolean =
          t.pos.isUnknown || t.pos.isSynthetic || t.pos.isZeroExtent ||
            t.isInstanceOf[TypeWrapper]

        private def isContextualClosure(dd: DefDef): Boolean =
            val params = dd.paramLists.collect { case Left(vs) => vs }.flatten
            params.nonEmpty && params.forall(v =>
              nm(v.name).startsWith("contextual$"),
            )

        /** The source-written trees inside a compiler-written node. */
        private def treesOnly(
            x: Any,
            sb: java.lang.StringBuilder,
            nodes: Array[Int],
        ): Unit = x match
            case t: Tree                             => walk(t, sb, nodes)
            case l: List[?]                          => l.foreach(treesOnly(_, sb, nodes))
            case o: Option[?]                        => o.foreach(treesOnly(_, sb, nodes))
            case p: Product if !p.isInstanceOf[Name] =>
              p.productIterator.foreach(treesOnly(_, sb, nodes))
            case _                                   => ()

        /** The canonical text of a tree in the "shape" mode of the S3 guard:
          * node kinds, names and literals of what the source wrote. A node that
          * the compiler wrote (implicit arguments, conversions,
          * context-function closures) is transparent, inferred types are
          * dropped, and a call keeps its name without its erased signature. So
          * a change in kyo-kernel (a new implicit lift) does not change the
          * hash of a kyo-ui method whose source did not change.
          */
        private def walk(
            x: Any,
            sb: java.lang.StringBuilder,
            nodes: Array[Int],
        ): Unit = x match
            case _: TypeWrapper if nodes(0) > 0                        => ()
            case t: Tree if nodes(0) > 0 && skipped(t)                 =>
              t.productIterator.foreach(f => treesOnly(f, sb, nodes))
            case Block(List(dd: DefDef), _: Lambda)
                if nodes(0) > 0 && isContextualClosure(dd) =>
              dd.rhs.foreach(walk(_, sb, nodes))
            case Apply(fun, List(arg)) if nodes(0) > 0 && skipped(fun) =>
              walk(arg, sb, nodes)
            case Apply(fun, args)
                if nodes(0) > 0 && args.nonEmpty && args.forall(skipped) =>
              walk(fun, sb, nodes)
            case TypeApply(fun, args)
                if nodes(0) > 0 && args.nonEmpty && args.forall(skipped) =>
              walk(fun, sb, nodes)
            case t: Tree                                               =>
              nodes(0) += 1
              discard(sb.append(t.productPrefix).append('('))
              t.productIterator.foreach { f =>
                walk(f, sb, nodes)
                discard(sb.append(','))
              }
              discard(sb.append(')'))
            case l: List[?]                                            =>
              discard(sb.append('['))
              l.foreach { e =>
                walk(e, sb, nodes)
                discard(sb.append(','))
              }
              discard(sb.append(']'))
            case o: Option[?]                                          => o.foreach(walk(_, sb, nodes))
            case SignedName(u, _, _)                                   => discard(sb.append(nm(u)))
            case n: Name                                               => discard(sb.append(nm(n)))
            case c: Constant                                           =>
              c.value match
                  case s: String =>
                    discard(sb.append('"').append(s).append('"'))
                  case t: Type   =>
                    discard(
                      sb.append("classOf[")
                        .append(tpe(t))
                        .append(']')
                        .append('#')
                        .append(c.tag),
                    )
                  case v         =>
                    discard(
                      sb.append(String.valueOf(v)).append('#').append(c.tag),
                    )
            case _: Symbol                                             => discard(sb.append('$'))
            case _: TypeMappable                                       => discard(sb.append('~'))
            case p: Product                                            =>
              discard(sb.append(p.productPrefix).append('('))
              p.productIterator.foreach { f =>
                walk(f, sb, nodes)
                discard(sb.append(','))
              }
              discard(sb.append(')'))
            case other                                                 => discard(sb.append(String.valueOf(other)))

        /** The first 8 bytes of the SHA-256, in hex. */
        private def sha(s: String): String =
          MessageDigest
            .getInstance("SHA-256")
            .digest(s.getBytes(UTF_8))
            .take(8)
            .map(b => f"${b & 0xff}%02x")
            .mkString

        /** "<sha256 prefix> n=<node count>" of the trees of `syms`. */
        private def fingerprint(syms: List[TermSymbol]): String =
          fingerprintOf(syms.flatMap(_.tree))

        /** "<sha256 prefix> n=<node count>" of `trees`. */
        // claude 2026-10-19: created (L1b review): from `fingerprint`
        private def fingerprintOf(trees: List[Tree]): String =
            val sb    = new java.lang.StringBuilder
            val nodes = Array(0)
            trees.foreach { t =>
              nodes(0) = 0
              walk(t, sb, nodes)
              discard(sb.append('\n'))
            }
            if sb.length == 0 then "NO-TREE"
            else s"${sha(sb.toString)} n=${nodes(0)}"
        end fingerprintOf

        /** The shape hash of each case of [[RuleNames.cssCases]] in the
          * top-level match of `kyo.internal.CssStyleRenderer.renderProp`: the
          * CSS of a position and of a display value that the runner copies.
          */
        // claude 2026-10-19: created (L1b review)
        def cssItems: List[Item] =
            val r                     =
              ctx.findStaticModuleClass("kyo.internal.CssStyleRenderer")
            val cases                 = List.newBuilder[CaseDef]
            def collect(x: Any): Unit = x match
                case m: Match                            => discard(cases ++= m.cases)
                case t: Tree                             => t.productIterator.foreach(collect)
                case l: List[?]                          => l.foreach(collect)
                case o: Option[?]                        => o.foreach(collect)
                case p: Product if !p.isInstanceOf[Name] =>
                  p.productIterator.foreach(collect)
                case _                                   => ()
            r.declarations
              .collect {
                case t: TermSymbol if nm(t.name) == "renderProp" => t
              }
              .flatMap(_.tree)
              .foreach(collect)
            def text(t: Tree): String =
                val sb = new java.lang.StringBuilder
                walk(t, sb, Array(1))
                sb.toString
            val all                   = cases.result()
            RuleNames.cssCases.map { name =>
              all.filter(c => text(c.pattern).contains(name)) match
                  case Nil =>
                    Item("css", s"${full(r)}.renderProp.$name", "MISSING")
                  case cs  =>
                    Item(
                      "css",
                      s"${full(r)}.renderProp.$name",
                      fingerprintOf(cs),
                    )
            }
        end cssItems

        /** The shape hash of each rule method of `kyo.internal.ReactiveUI` that
          * the runner copies (the bubbling and activation rules of `Dispatch`,
          * the region walk of `Mount`).
          */
        // claude 2026-10-13: created (C3, the `rule` items of the S3 guard)
        def ruleItems: List[Item] =
            val ui = ctx.findStaticModuleClass("kyo.internal.ReactiveUI")
            ui.declarations
              .collect {
                case t: TermSymbol
                    if !t.isSynthetic && !t.isParamAccessor &&
                      RuleNames.reactiveUi(nm(t.name)) =>
                  t
              }
              .groupBy(t => nm(t.name))
              .toList
              .map((name, syms) =>
                Item(
                  "rule",
                  s"${full(ui)}.$name",
                  fingerprint(syms.sortBy(s => tpe(s.declaredType))),
                ),
              )
        end ruleItems

        /** The hash of each client-JS fragment whose rules `Dispatch` copies
          * (keydown: Tab, the focus trap and group, Enter and Space; click; the
          * focusAuto seeding and its restore).
          */
        // claude 2026-10-13: created (C3, the `js` items of the S3 guard,
        // without the hash of the whole script)
        def jsItems: List[Item] =
            val renderer                         =
              ctx.findStaticModuleClass("kyo.internal.HtmlRenderer")
            val syms                             = renderer.declarations.collect {
              case t: TermSymbol if nm(t.name) == "clientJs" => t
            }
            val parts                            = List.newBuilder[String]
            def lits(x: Any): Unit               = x match
                case Literal(c) if c.value.isInstanceOf[String] =>
                  discard(parts += c.value.asInstanceOf[String])
                case t: Tree                                    => t.productIterator.foreach(lits)
                case l: List[?]                                 => l.foreach(lits)
                case o: Option[?]                               => o.foreach(lits)
                case p: Product if !p.isInstanceOf[Name]        =>
                  p.productIterator.foreach(lits)
                case _                                          => ()
            syms.flatMap(_.tree).foreach(lits)
            val lines                            = parts
              .result()
              .mkString("\u0000")
              .split("\n")
              .toList
              .map(_.replaceFirst("""^\s*\|""", ""))
            def normal(ls: List[String]): String =
              ls.filterNot(_.trim.startsWith("//"))
                .mkString("\n")
                .replaceAll("""\s+""", " ")
                .replaceAll("""\s*([(){}\[\],;=:|&+<>])\s*""", "$1")
            RuleNames.jsFragments.map { (label, start, end) =>
              val s = lines.indexWhere(l => start.r.findFirstIn(l).isDefined)
              if s < 0 then Item("js", label, "MISSING")
              else
                  val e    =
                    lines.indexWhere(l => end.r.findFirstIn(l).isDefined, s + 1)
                  val frag = lines.slice(s, if e < 0 then lines.size else e + 1)
                  Item("js", label, s"${sha(normal(frag))} lines=${frag.size}")
            }
        end jsItems

        // claude 2026-10-19: changed (L1b review): the css items
        def all: List[Item] =
          (astTypeItems ++ propCaseItems ++ astFieldItems ++ boundCaseItems ++
            signalMethodItems ++ attrsFieldItems ++ elementMemberItems ++
            eventItems ++ keyboardCaseItems ++ pinItems ++ ruleItems ++ jsItems ++
            cssItems)
            .distinctBy(_.key)
            .sortBy(i => (i.kind, i.name))
    end Reader
end KyoUiSurface
