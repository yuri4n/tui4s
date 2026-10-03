package gen

import KyoUiApi.{*, given}
import java.nio.file.Files
import java.nio.file.Path
import tastyquery.Symbols.*
import tastyquery.Types.*

/** Generates the ZIO and the Cats mirror of the kyo-ui DSL from the TASTy of kyo-ui.
  *
  * What the generator reads from TASTy: the element classes of the subset, their kyo-ui traits (linearization), the
  * public setters of each class and trait (name, parameter names and types, result kind), the element factories of
  * `kyo.UI`. What it writes, per ecosystem:
  *   - an opaque type per element and per trait, with the kyo-ui subtype relations as upper bounds, so that no kyo-ui
  *     member (and so no `Any < Async` setter) is in scope in user code;
  *   - a factory per element (`div`, `button`, ...), with a tui4s-owned `Here` in place of `kyo.Frame`;
  *   - an extension method per setter, on `[E <: Trait]` for a trait setter (one forwarder for all elements);
  *   - type aliases for the kyo-ui data types that the setters use (`KeyboardEvent`, ...).
  *
  * The translation of a parameter type is a small table of rules (see [[Rule]]); the ecosystem decides only the
  * effect points: an action, a handler, a two-way bound ref. A setter with a type that no rule covers is left out and
  * listed in the report, so a new kyo-ui release shows its new effect points at once.
  */
object Generate:

    /** The subset of the prototype. */
    val subset: List[String] = List("Div", "P", "SpanElement", "H1", "Button", "Input", "Checkbox", "Ul", "Li")

    /** How one parameter crosses from the mirror to kyo-ui. */
    enum Rule derives CanEqual:
        case Plain(tpe: String)   // same type on both sides
        case Data(name: String)   // a kyo-ui data type, aliased in the mirror
        case Action               // => Any < Async
        case Handler(in: Rule)    // In => Any < Async
        case Sig(elem: Rule)      // kyo.Signal[X] (read-only, the mirror's Signal[X])
        case Ref(elem: Rule)      // kyo.SignalRef[X] (two-way)
        case Children             // HtmlChildVal*
        case Pairs                // (String, String)*
        // claude 2026-10-03: created two rules for pure (non-effect) parameters: varargs of a data type, a pure function
        case Repeated(elem: Rule) // X* for a plain or data X (FileInput.accept(FileAccept*))
        case Pure(in: Rule, out: Rule) // X => Y with no effect (Element.style(Style.type => Style))
    end Rule

    /** The effect points of one ecosystem. */
    trait Eco:
        def name: String
        def pkg: String
        def imports: List[String]
        def extraTypeParams(rules: List[Rule]): String
        def usingClause(rules: List[Rule]): String
        def actionType: String
        def handlerType(in: String): String
        def refType(elem: String): String
        def actionExpr(p: String): String  = s"Bridge.action($p)"
        def handlerExpr(p: String): String = s"Bridge.handler($p)"
        def refExpr(p: String): String     = s"Bridge.ref($p)"
        def refChildConversion: String
        def refExtensions: String
        def wrongHandler(name: String): String
    end Eco

    private def hasEffect(rules: List[Rule]): Boolean = rules.exists {
        case Rule.Action | Rule.Handler(_) | Rule.Ref(_) => true
        case _                                           => false
    }

    object Zio extends Eco:
        val name    = "zio"
        val pkg     = "tui4s.interop.zio.ui"
        // claude 2026-10-03: changed the ZIO ref type from zio.stream.SubscriptionRef (sealed, so the facade kept a
        // second cell and raced) to the facade's own single-cell SignalRef (same package, so no import)
        val imports = List("_root_.zio.ZIO", s"$pkg.internal.Bridge")
        def extraTypeParams(rules: List[Rule]) = ""
        def usingClause(rules: List[Rule])     = ""
        val actionType                         = "ZIO[Any, Nothing, Any]"
        def handlerType(in: String)            = s"$in => ZIO[Any, Nothing, Any]"
        def refType(elem: String)              = s"SignalRef[$elem]"
        val refChildConversion =
            """    /** A `SignalRef[String]` made by `useSignal` as a reactive text child. */
              |    implicit def fromRefString(r: SignalRef[String])(using h: Here): Child =
              |        kyo.UI.Ast.HtmlChildVal.liftSignalString(Bridge.signal(r))(using h)""".stripMargin
        val refExtensions =
            """extension [A](ref: SignalRef[A])
              |    /** The reactive view of the ref. */
              |    def signal: Signal[A] = Bridge.signal(ref)
              |    /** A reactive subtree: `f` runs for every value of `ref`. */
              |    def render[N <: HtmlNode](f: A => N)(using h: Here): HtmlNode =
              |        kyo.UI.render(Bridge.signal(ref))[kyo.UI](f)(using h)""".stripMargin
        def wrongHandler(name: String) =
            s"tui4s: `$name` takes a ZIO[Any, Nothing, Any] (or event => ZIO[Any, Nothing, Any]). " +
                "Handle the errors of a ZIO that can fail (orDie, catchAll); a value of another type would never run."
    end Zio

    object Cats extends Eco:
        val name    = "cats"
        val pkg     = "tui4s.interop.cats.ui"
        val imports = List(
            "_root_.cats.effect.kernel.Async",
            "_root_.fs2.concurrent.SignallingRef",
            s"$pkg.internal.Bridge"
        )
        def extraTypeParams(rules: List[Rule]) = if hasEffect(rules) then "[F[_]]" else ""
        def usingClause(rules: List[Rule]) =
            if rules.exists(r => r == Rule.Action || r.isInstanceOf[Rule.Handler]) then "(using Async[F])" else ""
        val actionType              = "F[Unit]"
        def handlerType(in: String) = s"$in => F[Unit]"
        def refType(elem: String)   = s"SignallingRef[F, $elem]"
        val refChildConversion =
            """    /** A `SignallingRef[F, String]` made by `useSignal` as a reactive text child. */
              |    implicit def fromRefString[F[_]](r: SignallingRef[F, String])(using h: Here): Child =
              |        kyo.UI.Ast.HtmlChildVal.liftSignalString(Bridge.signal(r))(using h)""".stripMargin
        val refExtensions =
            """extension [F[_], A](ref: SignallingRef[F, A])
              |    /** The reactive view of a ref made by `useSignal` (fails for another SignallingRef). */
              |    def signal: Signal[A] = Bridge.signal(ref)
              |    /** A reactive subtree: `f` runs for every value of `ref`. */
              |    def render[N <: HtmlNode](f: A => N)(using h: Here): HtmlNode =
              |        kyo.UI.render(Bridge.signal(ref))[kyo.UI](f)(using h)""".stripMargin
        def wrongHandler(name: String) =
            s"tui4s: `$name` takes an F[Unit] (or event => F[Unit]) for an F with a cats.effect.Async; " +
                "use .void for an F[A]; a value of another type would never run."
    end Cats

    // ---------------------------------------------------------------------------------------------------------------
    // Reading kyo-ui
    // ---------------------------------------------------------------------------------------------------------------

    private val dataTypes = Map(
        "UI$.this.MouseEvent"    -> "MouseEvent",
        "UI$.this.KeyboardEvent" -> "KeyboardEvent",
        "UI$.this.WheelEvent"    -> "WheelEvent",
        "UI$.this.InputFilter"   -> "InputFilter",
        "UI$.this.Href"          -> "Href",
        "UI$.this.Target"        -> "Target",
        "UI$.this.ImgSrc"        -> "ImgSrc",
        "kyo.Style"              -> "Style",
        // claude 2026-10-03: added FileAccept (FileInput.accept) and the companion object type of Style (Element.style)
        "UI$.this.FileAccept"    -> "FileAccept",
        "kyo.Style.type"         -> "StyleCompanion"
    )

    /** The kyo-ui data types that the fields of an aliased data type name: they get an alias too. */
    // claude 2026-10-03: created, so a user can build a FileAccept.Image(ImageExt.Png) without a kyo import
    private val fieldDataTypes = Map(
        "FileAccept" -> List("ImageExt")
    )

    /** The top-level Kyo data types that an aliased data type needs: an alias, and a forwarder for each extension
      * method of their file (`2.pct`, `2.em`: without them a Style needs `import kyo.*`).
      */
    // claude 2026-10-03: created, after the general leak check showed kyo.Length in most Style builders
    private val topLevelData = Map(
        "Style" -> List("Length")
    )

    private val plain = Map(
        "scala.Predef.String" -> "String",
        "scala.Int"           -> "Int",
        "scala.Boolean"       -> "Boolean",
        "scala.Double"        -> "Double"
    )

    private val Effect = "kyo.kernel$package.<[scala.Any, kyo.Async$package.Async]"

    /** The rule for one parameter type of a kyo-ui setter, if there is one. */
    def rule(t: String): Option[Rule] =
        val Fn  = """scala\.Function1\[(.+?), (.+)\]""".r
        val Sig = """kyo\.Signal\[(.+)\]""".r
        val Ref = """kyo\.Signal\$package\.SignalRef\[(.+)\]""".r
        t match
            case p if plain.contains(p)                            => Some(Rule.Plain(plain(p)))
            case d if dataTypes.contains(d)                        => Some(Rule.Data(dataTypes(d)))
            case s"=> $e" if e == Effect                           => Some(Rule.Action)
            case Fn(in, out) if out == Effect                      => rule(in).collect { case r @ (_: Rule.Plain | _: Rule.Data) => Rule.Handler(r) }
            // claude 2026-10-03: added the pure function rule (no effect in the result, plain or data on both sides)
            case Fn(in, out) if !out.contains("kyo.kernel$package.<[") =>
                for
                    i <- rule(in).collect { case r @ (_: Rule.Plain | _: Rule.Data) => r }
                    o <- rule(out).collect { case r @ (_: Rule.Plain | _: Rule.Data) => r }
                yield Rule.Pure(i, o)
            case Ref(elem)                                         => rule(elem).collect { case r: Rule.Plain => Rule.Ref(r) }
            case Sig(elem)                                         => rule(elem).collect { case r @ (_: Rule.Plain | _: Rule.Data) => Rule.Sig(r) }
            case "Ast$.this.HtmlChildVal*"                         => Some(Rule.Children)
            case "scala.Tuple2[scala.Predef.String, scala.Predef.String]*" => Some(Rule.Pairs)
            // claude 2026-10-03: added the varargs rule for a plain or data element type
            case s"$elem*" => rule(elem).collect { case r @ (_: Rule.Plain | _: Rule.Data) => Rule.Repeated(r) }
            case _                                                 => None
        end match
    end rule

    /** One setter: its kyo-ui owner, name, parameters, and whether it returns a reactive node. */
    final case class Setter(
        owner: ClassSymbol,
        name: String,
        params: List[(String, String)],
        reactive: Boolean,
        needsFrame: Boolean
    ):
        def key: (String, List[String]) = (name, params.map(_._2))
        def rules: Option[List[Rule]]   = params.foldRight(Option(List.empty[Rule])) { case ((_, t), acc) =>
            for r <- rule(t); rs <- acc yield r :: rs
        }
    end Setter

    def setterOf(owner: ClassSymbol, t: TermSymbol): Setter =
        def params(tpe: TypeOrMethodic): List[(String, String)] = tpe match
            case m: MethodType if !m.isImplicitOrContextual =>
                m.paramNames.map(_.toString).zip(m.paramTypes.map(_.showBasic)) ++ params(m.resultType)
            case m: MethodType => params(m.resultType)
            case p: PolyType   => params(p.resultType)
            case _             => Nil
        def contextual(tpe: TypeOrMethodic): List[String] = tpe match
            case m: MethodType if m.isImplicitOrContextual => m.paramTypes.map(_.showBasic) ++ contextual(m.resultType)
            case m: MethodType                             => contextual(m.resultType)
            case p: PolyType                               => contextual(p.resultType)
            case _                                         => Nil
        val reactive   = finalResult(t.declaredType).showBasic.contains("Reactive[")
        val needsFrame = contextual(t.declaredType).exists(_.contains("Frame"))
        Setter(owner, t.name.toString, params(t.declaredType), reactive, needsFrame)
    end setterOf

    def declaredSetters(c: ClassSymbol): List[Setter] =
        c.declarations.collect {
            case t: TermSymbol if isPublic(t) && t.isMethod && setters(c).exists(_.sym == t) => setterOf(c, t)
        }.sortBy(s => (s.name, s.params.map(_._2).mkString))

    def mirrorName(c: ClassSymbol): String = c.name.toString match
        case "HtmlContent" => "HtmlNode"
        case other         => other

    // ---------------------------------------------------------------------------------------------------------------
    // Writing the mirror
    // ---------------------------------------------------------------------------------------------------------------

    final case class Output(source: String, report: List[String])

    private val keywords = Set(
        "abstract", "case", "catch", "class", "def", "do", "else", "enum", "export", "extends", "false", "final",
        "finally", "for", "given", "if", "implicit", "import", "lazy", "match", "new", "null", "object", "override",
        "package", "private", "protected", "return", "sealed", "super", "then", "throw", "trait", "true", "try", "type",
        "val", "var", "while", "with", "yield"
    )

    /** A kyo-ui name as a Scala identifier (`for` -> `\`for\``). */
    def ident(n: String): String = if keywords(n) then s"`$n`" else n


    /** Every HTML element case class of kyo-ui (the SVG ones live in `kyo.Svg`). */
    lazy val allHtml: List[String] =
        elements.filter(_.owner == astModule).map(_.name.toString).sorted

    def generate(eco: Eco, subset: List[String] = Generate.subset): Output =
        def mirrorTypeOf(r: Rule): String = r match
            case Rule.Plain(t)    => t
            case Rule.Data(n)     => n
            case Rule.Action      => eco.actionType
            case Rule.Handler(in) => eco.handlerType(mirrorTypeOf(in))
            case Rule.Sig(e)      => s"Signal[${mirrorTypeOf(e)}]"
            case Rule.Ref(e)      => eco.refType(mirrorTypeOf(e))
            case Rule.Children    => "Child*"
            case Rule.Pairs       => "(String, String)*"
            // claude 2026-10-03: added the mirror types of the two new rules
            case Rule.Repeated(e) => s"${mirrorTypeOf(e)}*"
            case Rule.Pure(i, o)  => s"${mirrorTypeOf(i)} => ${mirrorTypeOf(o)}"

        def argExprOf(p: String, r: Rule): String = r match
            case Rule.Action     => eco.actionExpr(p)
            case Rule.Handler(_) => eco.handlerExpr(p)
            case Rule.Ref(_)     => eco.refExpr(p)
            case Rule.Children   => s"$p*"
            case Rule.Pairs      => s"$p*"
            // claude 2026-10-03: added the argument of a varargs rule (a pure function is passed as is)
            case Rule.Repeated(_) => s"$p*"
            case _               => p

        val classes    = subset.map(n => ctx.findStaticClass(s"kyo.UI.Ast.$n"))
        val htmlContent = ctx.findStaticClass("kyo.UI.Ast.HtmlContent")
        // The kyo-ui traits of the subset: every node class in their linearization, except kyo.UI itself.
        // HtmlContent is the mirror's HtmlNode, which the header declares (it also covers reactive nodes).
        val traits = classes.flatMap(_.linearization).distinct.filter(c =>
            c != uiClass && c != htmlContent && c.isSubClass(uiClass) && !classes.contains(c)
        )
        val inMirror = (traits ++ classes).toSet

        def parents(c: ClassSymbol): List[String] =
            val ps = c.parentClasses.filter(p => inMirror(p) || p == htmlContent).map(mirrorName)
            if ps.isEmpty then List("UI") else ps

        def kyoName(c: ClassSymbol): String = c.displayFullName.replace("$", "")

        val opaqueTypes = (traits ++ classes).map { c =>
            val bound = parents(c).mkString(" & ")
            val doc   = s"/** kyo-ui's `${kyoName(c)}`, with no kyo-ui member in scope. */"
            s"$doc\nopaque type ${mirrorName(c)} <: $bound = ${kyoName(c)}"
        }

        // A factory with parameters (`img(src, alt)`, `dropdown(options*)`) goes through the same rules as a setter.
        val factoryList = uiMembers.filter(isElementFactory).filter(m =>
            resultClass(finalResult(m.sym.declaredType)).exists(classes.contains)
        ).sortBy(_.name).map { m =>
            val cls    = resultClass(finalResult(m.sym.declaredType)).get
            val setter = setterOf(cls, m.sym)
            setter.rules match
                case Some(rules) =>
                    val ps   = setter.params.zip(rules).map { case ((n, _), r) => s"$n: ${mirrorTypeOf(r)}" }
                    val args = setter.params.zip(rules).map { case ((n, _), r) => s"$n = ${argExprOf(n, r)}" }
                    val pl   = if ps.isEmpty then "" else ps.mkString("(", ", ", ")")
                    val al   = if args.isEmpty then "" else args.mkString("(", ", ", ")")
                    Right(s"def ${m.name}$pl(using h: Here): ${mirrorName(cls)} = kyo.UI.${m.name}$al(using h)")
                case None => Left(s"skipped factory kyo.UI.${m.name}(${setter.params.map(_._2).mkString(", ")}): no rule")
        }
        val factories        = factoryList.collect { case Right(f) => f }
        val skippedFactories = factoryList.collect { case Left(r) => r }

        // Setters at the highest kyo-ui level that declares them; a class-level override of a trait setter is skipped.
        // claude 2026-10-03: changed: a class setter is left out only when a trait OF THAT CLASS declares the same
        // setter. It was left out when ANY trait of the mirror declared it, so Opt.value(String) was lost to
        // TextInput.value(String), and the report did not list it as skipped.
        val traitSetters = traits.map(t => t -> declaredSetters(t).map(_.key).toSet).toMap
        def inheritedKeys(c: ClassSymbol): Set[(String, List[String])] =
            c.linearization.filter(traitSetters.contains).flatMap(traitSetters).toSet
        val all = traits.flatMap(declaredSetters) ++
            classes.flatMap(c => declaredSetters(c).filterNot(s => inheritedKeys(c)(s.key)))

        def dataNames(r: Rule): List[String] = r match
            case Rule.Data(n)     => List(n)
            case Rule.Handler(in) => dataNames(in)
            case Rule.Sig(e)      => dataNames(e)
            case Rule.Ref(e)      => dataNames(e)
            // claude 2026-10-03: added the data types of the two new rules
            case Rule.Repeated(e) => dataNames(e)
            case Rule.Pure(i, o)  => dataNames(i) ++ dataNames(o)
            case _                => Nil
        val usedData = all.flatMap(_.rules.toList.flatten).flatMap(dataNames).distinct

        final case class Block(receiver: String, kyoType: String, isTrait: Boolean)

        val skipped = all.filter(_.rules.isEmpty).map(s =>
            s"skipped ${kyoName(s.owner)}.${s.name}(${s.params.map(_._2).mkString(", ")}): no rule"
        )
        // All overloads of one name live in ONE extension block: Scala resolves an overloaded extension by its receiver
        // first and does not come back, so a more specific receiver in another block would hide the right overload.
        val methods = all.flatMap { s =>
            s.rules match
                case None => Nil
                case Some(rules) =>
                    val isTrait = traits.contains(s.owner)
                    val recv    = mirrorName(s.owner)
                    val block   = Block(recv, kyoName(s.owner), isTrait)
                    val tparams = eco.extraTypeParams(rules)
                    val ps      = s.params.zip(rules).map { case ((n, _), r) => s"${ident(n)}: ${mirrorTypeOf(r)}" }
                    val paramList = if s.params.isEmpty then "" else ps.mkString("(", ", ", ")")
                    val args = s.params.zip(rules).map { case ((n, _), r) => s"${ident(n)} = ${argExprOf(ident(n), r)}" }
                    val call0 = s"(e: ${kyoName(s.owner)}).${ident(s.name)}" + (if s.params.isEmpty then "" else args.mkString("(", ", ", ")"))
                    // A kyo-ui setter that wants a Frame gets the user's call site through `Here`.
                    val call = if s.needsFrame then s"{ given kyo.Frame = h; $call0 }" else call0
                    // A trait setter that returns a reactive node is for HTML elements only (an SVG mirror would return
                    // its own node type): the evidence keeps it in the same block as its overloads.
                    val usingParts =
                        List(eco.usingClause(rules).stripPrefix("(using ").stripSuffix(")")).filter(_.nonEmpty) ++
                            (if s.reactive && isTrait then List("html: E <:< HtmlNode") else Nil) ++
                            (if s.needsFrame then List("h: Here") else Nil)
                    val usingClause = if usingParts.isEmpty then "" else usingParts.mkString("(using ", ", ", ")")
                    val result =
                        if s.reactive then "HtmlNode"
                        else if isTrait then "E"
                        else recv
                    val body =
                        if s.reactive || !isTrait then call
                        else s"$call.asInstanceOf[E]"
                    // The doc names the kyo-ui setter but not its Kyo signature: IDE hovers show it to the user.
                    val doc = s"/** Forwards to kyo-ui's `${kyoName(s.owner).stripPrefix("kyo.")}.${s.name}`. */"
                    List(block -> s"$doc\ndef ${ident(s.name)}$tparams$paramList$usingClause: $result =\n    $body")
        }

        // One catch-all per handler name: a precise compile error instead of Scala's "does not take parameters" when
        // no overload fits (a plain value, a ZIO that can fail, a Kyo effect).
        val handlerNames = all.filter(s => s.rules.exists(_.exists(r => r == Rule.Action || r.isInstanceOf[Rule.Handler])))
            .map(s => Block(mirrorName(s.owner), kyoName(s.owner), traits.contains(s.owner)) -> s.name).distinct
        val catchAlls = handlerNames.map { (b, n) =>
            val result = if b.isTrait then "E" else b.receiver
            b -> s"""/** Not a handler: a compile error that says what a handler is. */
                    |inline def $n[A](inline other: A)(using DummyImplicit): $result =
                    |    scala.compiletime.error("${eco.wrongHandler(n)}")""".stripMargin
        }

        val extensionBlocks = (methods ++ catchAlls).groupBy(_._1).toList.sortBy((b, _) => b.receiver).map { (b, ms) =>
            val header =
                if b.isTrait then s"extension [E <: ${b.receiver}](e: E)"
                else s"extension (e: ${b.receiver})"
            (header :: ms.map(m => m._2.linesIterator.map("    " + _).mkString("\n"))).mkString("\n")
        }

        // claude 2026-10-03: changed: the field data types of a used data type get an alias too (FileAccept -> ImageExt),
        // and StyleCompanion (the type of the Style object, for `style(_.bold)`) is a type alias only
        val extraData = usedData.flatMap(n => fieldDataTypes.getOrElse(n, Nil)).filterNot(usedData.contains).distinct
        // claude 2026-10-03: added the top-level data types (Length) and the forwarders of their extension methods
        val topData = (usedData ++ extraData).flatMap(n => topLevelData.getOrElse(n, Nil)).distinct
        val topExtensions = topData.flatMap { n =>
            val files = ctx.findPackage("kyo").declarations.collect {
                case c: ClassSymbol if c.isModuleClass && c.name.toString.startsWith(n + "$package") => c
            }
            files.flatMap(_.declarations.collect { case t: TermSymbol if isPublic(t) && t.isExtensionMethod => t })
                .flatMap { t =>
                    t.declaredType match
                        case m: MethodType if m.paramTypes.size == 1 =>
                            val recv = m.paramTypes.head.showBasic
                            val res  = finalResult(t.declaredType).showBasic.replace(s"kyo.$n", n)
                            plain.get(recv).map(r => s"extension (v: $r) def ${t.name}: $res = kyo.${t.name}(v)")
                        case _ => None
                }
                .sorted
        }
        val dataAliases = (usedData ++ extraData).flatMap {
            case "Style"          => List("type Style = kyo.Style", "val Style: kyo.Style.type = kyo.Style")
            case "StyleCompanion" => List("type StyleCompanion = kyo.Style.type")
            case n                => List(s"type $n = kyo.UI.$n", s"val $n: kyo.UI.$n.type = kyo.UI.$n")
        } ++ List( // the field types of KeyboardEvent, so that `e.key == Keyboard.Enter` needs no kyo import
            "type Keyboard = kyo.UI.Keyboard",
            "val Keyboard: kyo.UI.Keyboard.type = kyo.UI.Keyboard",
            "type Modifiers = kyo.UI.Modifiers",
            "val Modifiers: kyo.UI.Modifiers.type = kyo.UI.Modifiers"
        ) ++ topData.flatMap(n => List(s"type $n = kyo.$n", s"val $n: kyo.$n.type = kyo.$n")) ++ topExtensions

        // Leaks through the data aliases: a public member of an aliased type (or of its companion, or of a nested class
        // such as an enum case) whose signature names a Kyo type that the mirror does not alias. A type nested in an
        // aliased type (`Style.Prop`, `Href.Absolute`) is reachable through the alias, so it is not a leak.
        // claude 2026-10-03: changed the leak check: it was a list of 7 token names, on the members of the class only;
        // it missed kyo.HttpUrl in Href.Absolute and ImgSrc.Absolute (enum cases)
        def fullName(n: String): String = n match
            case "Style" | "StyleCompanion" => "kyo.Style"
            case other                      => s"kyo.UI.$other"
        val aliased = ((usedData ++ extraData).map(fullName) ++ List("kyo.UI.Keyboard", "kyo.UI.Modifiers") ++
            topData.map(n => s"kyo.$n")).distinct
        def kyoRefs(sig: String): List[String] =
            """(?:kyo|UI\$\.this|Ast\$\.this|Style\$\.this)(?:\.[A-Za-z0-9_$]+)+""".r.findAllIn(sig).toList.map { r =>
                val q = r.replace("UI$.this.", "kyo.UI.").replace("Ast$.this.", "kyo.UI.Ast.").replace("Style$.this.", "kyo.Style.")
                if q.endsWith("kernel$package") then "kyo.<" else q.replaceAll("""\.[A-Za-z0-9_]+\$package""", "")
            }.distinct
        def allowedRef(r: String): Boolean = aliased.exists(a => r == a || r.startsWith(a + "."))
        def classesOf(full: String): List[ClassSymbol] =
            def nested(c: ClassSymbol): List[ClassSymbol] =
                c :: c.declarations.collect { case n: ClassSymbol if isPublic(n) => n }.flatMap(nested)
            val roots = List(
                scala.util.Try(ctx.findStaticClass(full)).toOption,
                scala.util.Try(ctx.findStaticModuleClass(full)).toOption
            ).flatten
            roots.flatMap(nested).distinct
        val plumbing = Set("copy", "unapply", "fromProduct", "productElement", "productElementName", "ordinal", "equals",
            "hashCode", "toString", "canEqual", "productArity", "productPrefix")
        val leaks = aliased.flatMap(classesOf).distinct.flatMap { c =>
            c.declarations.collect {
                case t: TermSymbol if isPublic(t) && !plumbing(t.name.toString) && !t.name.toString.startsWith("copy$") &&
                        !t.name.toString.startsWith("_") && kyoRefs(t.declaredType.showBasic).exists(r => !allowedRef(r)) =>
                    val names = kyoRefs(t.declaredType.showBasic).filterNot(allowedRef).mkString(", ")
                    s"leak through alias: ${c.displayFullName.replace("$", "")}.${t.name} names $names: ${t.declaredType.showBasic}"
            }
        }

        val reactiveCore = s"""
            |// ---- reactive values (hand-written template, the same for every ecosystem) ----
            |
            |/** A read-only reactive value: kyo-ui's `kyo.Signal`, under a tui4s name. Covariant: it is only read. */
            |opaque type Signal[+A] = kyo.Signal[A @uncheckedVariance]
            |
            |object Signal:
            |    /** A value that never changes. */
            |    def const[A](a: A)(using CanEqual[A, A], Here): Signal[A] = kyo.Signal.initConst(a)
            |
            |    private[ui] def fromKyo[A](s: kyo.Signal[A]): Signal[A] = s
            |    private[ui] def toKyo[A](s: Signal[A]): kyo.Signal[A]   = s.asInstanceOf[kyo.Signal[A]]
            |end Signal
            |
            |extension [A](s: Signal[A])
            |    /** A derived value; re-computed when `s` changes. */
            |    def map[B](f: A => B)(using CanEqual[B, B], Here): Signal[B] = (s: kyo.Signal[A]).map(f)
            |    /** A reactive subtree: `f` runs for every value of `s`. */
            |    def render[N <: HtmlNode](f: A => N)(using h: Here): HtmlNode = kyo.UI.render(s: kyo.Signal[A])[kyo.UI](f)(using h)
            |
            |extension [A](s: Signal[Seq[A]])
            |    /** One child per element, reconciled by position. */
            |    def foreach[N <: HtmlNode](f: A => N)(using h: Here, eq: CanEqual[A, A]): HtmlNode =
            |        kyo.UI.foreach(chunks(s))[kyo.UI](f)(using h)
            |    /** One child per element, identified by `key`. */
            |    def foreachKeyed[N <: HtmlNode](key: A => String)(f: A => N)(using h: Here, eq: CanEqual[A, A]): HtmlNode =
            |        kyo.UI.foreachKeyed(chunks(s))[kyo.UI](key)(f)(using h)
            |
            |private def chunks[A](s: Signal[Seq[A]])(using h: Here, eq: CanEqual[A, A]): kyo.Signal[kyo.Chunk[A]] =
            |    given CanEqual[kyo.Chunk[A], kyo.Chunk[A]] = CanEqual.derived
            |    (s: kyo.Signal[Seq[A]]).map(seq => kyo.Chunk.from(seq))(using summon, h)
            |
            |${eco.refExtensions}
            |
            |/** Shows `body` while `condition` is true. */
            |def when[N <: HtmlNode](condition: Signal[Boolean])(body: => N)(using h: Here): HtmlNode =
            |    kyo.UI.when[kyo.UI](condition)(body)(using h)
            |
            |/** The empty node. */
            |def empty(using h: Here): HtmlNode = kyo.UI.empty(using h)
            |
            |/** A child of an HTML container: a node, a text, a reactive text or subtree. */
            |opaque type Child = kyo.UI.Ast.HtmlChildVal
            |
            |object Child:
            |    implicit def fromNode(n: HtmlNode): Child = kyo.UI.Ast.HtmlChildVal.lift[kyo.UI](n)
            |    implicit def fromString(s: String)(using h: Here): Child = kyo.UI.Ast.HtmlChildVal.liftString(s)(using h)
            |    implicit def fromSignalString(s: Signal[String])(using h: Here): Child =
            |        kyo.UI.Ast.HtmlChildVal.liftSignalString(s)(using h)
            |    implicit def fromSignalNode[N <: HtmlNode](s: Signal[N])(using h: Here): Child =
            |        kyo.UI.Ast.HtmlChildVal.lift[kyo.UI](kyo.UI.render(Signal.toKyo(s))[kyo.UI](n => n)(using h))
            |${eco.refChildConversion}
            |end Child
            |
            |// ---- conversions for the hand-written runtime of this package (not for users) ----
            |// Top-level, not in an object: an opaque type is transparent only in the top-level scope of this file.
            |
            |private[$lastSegment] def unsafeToKyo(u: UI): kyo.UI          = u
            |private[$lastSegment] def unsafeFromKyo(u: kyo.UI): HtmlNode  = u
            |private[$lastSegment] def unsafeFrame(h: Here): kyo.Frame     = h
            |""".stripMargin

        val header = s"""// GENERATED by gen/Generate.scala from the TASTy of io.getkyo::kyo-ui:1.0.0-RC7. Do not edit.
            |// Ecosystem: ${eco.name}. Elements: ${subset.mkString(", ")}.
            |package ${eco.pkg}
            |
            |import scala.annotation.unchecked.uncheckedVariance
            |import scala.language.implicitConversions
            |${eco.imports.map("import " + _).mkString("\n")}
            |
            |// ---- the source position of a node: kyo.Frame under a tui4s name, made at the call site ----
            |
            |/** The source position that kyo-ui keeps in a node. Users never write it: it is made at each call site. */
            |opaque type Here = kyo.Frame
            |
            |object Here:
            |    implicit inline def derive: Here = kyo.Frame.derive
            |
            |// ---- nodes: opaque, so that no kyo-ui member (no `Any < Async` setter) is in scope ----
            |
            |/** A UI node: kyo-ui's `kyo.UI`. */
            |opaque type UI = kyo.UI
            |
            |/** A node that an HTML container accepts (an element, a text, a reactive subtree). */
            |opaque type HtmlNode <: UI = kyo.UI
            |""".stripMargin

        val src = List(
            header,
            opaqueTypes.mkString("\n\n"),
            "\n// ---- factories ----\n",
            factories.mkString("\n"),
            "\n// ---- setters ----\n",
            extensionBlocks.mkString("\n\n"),
            "\n// ---- kyo-ui data types (pure values) ----\n",
            dataAliases.mkString("\n"),
            reactiveCore
        ).mkString("\n")

        val summary = List(
            s"elements: ${classes.size}, traits: ${traits.size} (${traits.map(mirrorName).mkString(", ")})",
            s"factories: ${factories.size}",
            s"setters generated: ${methods.size} (of ${all.size} at their highest level)",
            s"  with an effect point: ${methods.count(m => m._2.contains("Bridge."))}",
            // claude 2026-10-03: changed: the summary also lists the field data aliases
            s"data aliases: ${usedData.mkString(", ")} + ${(extraData ++ List("Keyboard", "Modifiers") ++ topData).mkString(", ")}",
            s"extension forwarders: ${topExtensions.size}"
        )
        Output(src, summary ++ skippedFactories ++ skipped ++ leaks)
    end generate

    lazy val lastSegment: String = "ui"
end Generate

@main def writeMirrors(outDir: String): Unit =
    List(Generate.Zio -> "zio/generated/ZioUi.scala", Generate.Cats -> "cats/generated/CatsUi.scala").foreach { (eco, rel) =>
        val out  = Generate.generate(eco)
        val path = Path.of(outDir).resolve(rel)
        Files.createDirectories(path.getParent)
        Files.writeString(path, out.source)
        println(s"== ${eco.name}: wrote $path (${out.source.linesIterator.size} lines)")
        out.report.foreach(l => println("   " + l))
    }

/** The whole HTML part of kyo-ui, to measure the size of a full mirror and the rules it still lacks. */
@main def writeFullMirror(outDir: String): Unit =
    val out  = Generate.generate(Generate.Zio, Generate.allHtml)
    val path = Path.of(outDir).resolve("full-zio/generated/ZioUiFull.scala")
    Files.createDirectories(path.getParent)
    Files.writeString(path, out.source)
    println(s"== full zio mirror: ${Generate.allHtml.size} elements, wrote $path (${out.source.linesIterator.size} lines)")
    out.report.foreach(l => println("   " + l))
