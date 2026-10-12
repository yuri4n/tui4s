package tui4s

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import scala.io.Source
import tastyquery.Classpaths.ClasspathEntry
import tastyquery.Contexts.Context
import tastyquery.Modifiers.Visibility
import tastyquery.Names.*
import tastyquery.Symbols.*
import tastyquery.Types.*
import tastyquery.jdk.ClasspathLoaders

/** The kyo-ui surface that the runner depends on, read from the TASTy of the
  * kyo-ui jar on the test class path (RC-12, C1 part): the types of
  * `kyo.UI.Ast` and the cases of `kyo.Style.Prop`. One line per item,
  * `kind<TAB>name<TAB>detail`, the same format as the spike S3 guard. C2 and C3
  * add the hashes of the dispatch and region rules.
  */
// claude 2026-10-12: created (from .work/spikes/s3-drift/guard/Guard.scala)
object KyoUiSurface:

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

        /** The cases of `kyo.Style.Prop`, with the fields of a case class. */
        def propCaseItems: List[Item] =
            val prop = ctx.findStaticClass("kyo.Style.Prop")
            prop.companionClass.toList.flatMap(_.declarations).collect {
              case t: TermSymbol if t.isEnumCase =>
                Item("enum.case", s"${full(prop)}.${nm(t.name)}", "-")
              case c: ClassSymbol
                  if c.isCaseClass && c.isEnum && !c.isModuleClass =>
                Item(
                  "enum.case",
                  s"${full(prop)}.${nm(c.name)}",
                  fields(c).mkString("(", ",", ")"),
                )
            }
        end propCaseItems

        def all: List[Item] =
          (astTypeItems ++ propCaseItems)
            .distinctBy(_.key)
            .sortBy(i => (i.kind, i.name))
    end Reader
end KyoUiSurface
