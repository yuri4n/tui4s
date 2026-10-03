package gen

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import tastyquery.Classpaths.ClasspathEntry
import tastyquery.Contexts.Context
import tastyquery.Modifiers.Visibility
import tastyquery.Symbols.*
import tastyquery.Types.*
import tastyquery.jdk.ClasspathLoaders

/** A read-only model of the public kyo-ui API, read from the TASTy files of the
  * kyo-ui jar on this JVM's class path (tasty-query 1.9.0).
  */
object KyoUiApi:

    /** One public method of kyo-ui: its owner, its name and its signature. */
    final case class Member(ownerSym: ClassSymbol, name: String, signature: String, sym: TermSymbol):
        def owner: String = ownerSym.displayFullName.replace("$", "")
        def mentions(token: String): Boolean = Tokens.mentions(signature, token)

    /** The tokens that the inventory counts in signatures. */
    object Tokens:
        val all: List[String] = List("<", "Async", "Signal", "SignalRef", "Stream", "Frame", "Chunk", "Maybe")

        // tasty-query prints `kyo.kernel$package.<[A, S]`, `kyo.Async$package.Async`, `kyo.Frame$package.Frame`,
        // `kyo.Signal[A]`, `kyo.Signal.SignalRef[A]`, `kyo.Chunk[A]`, `kyo.Maybe$package.Maybe[A]`.
        def mentions(signature: String, token: String): Boolean = token match
            case "<" => signature.contains("kernel$package.<[")
            case t   => ("kyo\\.([A-Za-z]+\\$package\\.|Signal[$.]+)?" + t + "(?![A-Za-z0-9_$.])").r.findFirstIn(signature).isDefined
    end Tokens

    private val paths: List[Path] =
        System.getProperty("java.class.path", "").split(File.pathSeparator).toList.filter(_.nonEmpty).map(Path.of(_))

    private val jrt: Path = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules", "java.base")

    private val classpath = ClasspathLoaders.read(jrt :: paths)

    given ctx: Context = Context.initialize(classpath)

    /** The class path entry of the kyo-ui jar. */
    val kyoUiEntry: ClasspathEntry =
        (jrt :: paths).zip(classpath).collectFirst {
            case (p, e) if p.getFileName.toString.startsWith("kyo-ui") => e
        }.getOrElse(sys.error("kyo-ui is not on the class path"))

    val uiClass: ClassSymbol      = ctx.findStaticClass("kyo.UI")
    val uiModule: ClassSymbol     = ctx.findStaticModuleClass("kyo.UI")
    val astModule: ClassSymbol    = ctx.findStaticModuleClass("kyo.UI.Ast")
    val elementClass: ClassSymbol = ctx.findStaticClass("kyo.UI.Ast.Element")

    def isPublic(sym: TermOrTypeSymbol): Boolean =
        sym.visibility == Visibility.Public && !sym.isSynthetic

    /** All classes of the kyo-ui jar, also nested ones. */
    lazy val allClasses: List[ClassSymbol] =
        def nested(c: ClassSymbol): List[ClassSymbol] =
            c :: c.declarations.collect { case n: ClassSymbol => n }.flatMap(nested)
        ctx.findSymbolsByClasspathEntry(kyoUiEntry).toList.collect { case c: ClassSymbol => c }.flatMap(nested).distinct
    end allClasses

    /** The final result type of a (possibly curried, polymorphic) method type. */
    def finalResult(t: TypeOrMethodic): Type = t match
        case m: MethodType => finalResult(m.resultType)
        case p: PolyType   => finalResult(p.resultType)
        case t: Type       => t

    /** The class that a result type names, when it names one. */
    def resultClass(t: Type): Option[ClassSymbol] = t match
        case a: AppliedType => resultClass(a.tycon)
        case r: TypeRef     => r.optSymbol.collect { case c: ClassSymbol => c }
        case _              => None

    def show(sym: TermSymbol): String = sym.declaredType.showBasic

    private val objectMembers = Set(
        "equals", "hashCode", "toString", "canEqual", "productArity", "productElement", "productPrefix",
        "productElementName", "productIterator", "productElementNames", "copy", "withAttrs", "ordinal", "fromProduct",
        "unapply", "<init>"
    )

    /** Every public method of every public kyo-ui class (case-class plumbing left out). */
    lazy val allMembers: List[Member] =
        allClasses.filter(c => isPublic(c)).flatMap { c =>
            c.declarations.collect {
                case t: TermSymbol if isPublic(t) && t.isMethod && !objectMembers(t.name.toString) &&
                        !t.name.toString.startsWith("copy$default") && !t.name.toString.startsWith("$") &&
                        !t.name.toString.startsWith("_") =>
                    Member(c, t.name.toString, show(t), t)
            }
        }

    /** The concrete element case classes (`Div`, `Button`, ..., and the SVG ones). */
    lazy val elements: List[ClassSymbol] =
        allClasses.filter(c => c.isCaseClass && !c.isModuleClass && isPublic(c) && c.isSubClass(elementClass))

    /** The setters of an element: public methods, declared on the element or on one of its kyo-ui traits, that
      * return a node (the element's own type, `Self`, a `Reactive`, a `UI`).
      */
    def setters(cls: ClassSymbol): List[Member] =
        cls.linearization.filter(c => c.isSubClass(elementClass)).flatMap { owner =>
            owner.declarations.collect {
                case t: TermSymbol if isPublic(t) && t.isMethod && !objectMembers(t.name.toString) &&
                        !t.name.toString.startsWith("copy$default") && isSetter(t) =>
                    Member(owner, t.name.toString, show(t), t)
            }
        }.groupBy(m => (m.name, m.signature)).values.map(_.head).toList.sortBy(m => (m.name, m.signature))

    /** A setter returns `Self` or a node (a class that extends `kyo.UI`); a getter returns anything else. */
    private def isSetter(t: TermSymbol): Boolean =
        val res = finalResult(t.declaredType)
        val isSelf = res match
            case r: TypeRef => r.name.toString == "Self"
            case _          => false
        isSelf || resultClass(res).exists(_.isSubClass(uiClass))
    end isSetter

    /** Public members of the `kyo.UI` companion. */
    lazy val uiMembers: List[Member] = allMembers.filter(_.ownerSym == uiModule)

    def isElementFactory(m: Member): Boolean =
        !m.sym.isExtensionMethod && !m.sym.isImplicit &&
            resultClass(finalResult(m.sym.declaredType)).exists(_.isSubClass(elementClass))
end KyoUiApi
