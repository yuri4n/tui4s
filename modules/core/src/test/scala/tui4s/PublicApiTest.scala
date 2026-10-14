package tui4s

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kyo.test.*
import tastyquery.Contexts.Context
import tastyquery.Modifiers.Visibility
import tastyquery.Symbols.*
import tastyquery.Types.*
import tastyquery.jdk.ClasspathLoaders

/** The seal of `tui4s.internal` (gate 1 item 1, RC-36), read from the TASTy of
  * the core's own classes with tasty-query: every top-level definition of
  * `tui4s.internal` is `private[tui4s]`, and no public signature of `tui4s`,
  * `tui4s.spi` or `tui4s.testkit` names a type of `tui4s.internal`.
  */
// claude 2026-10-13: created (C3)
class PublicApiTest extends Test[Any]:

    /** The TASTy reader over this JVM's class path, and the class path entry of
      * the core's main classes (the one that holds `tui4s/Tui.tasty`).
      */
    private lazy val reader: (Context, List[Symbol]) =
        val cp      = java.lang.System
          .getProperty("java.class.path", "")
          .split(File.pathSeparator)
          .toList
          .filter(_.nonEmpty)
          .map(Path.of(_))
        val jrt     = FileSystems
          .getFileSystem(URI.create("jrt:/"))
          .getPath("modules", "java.base")
        val all     = jrt :: cp
        val entries = ClasspathLoaders.read(all)
        val ctx     = Context.initialize(entries)
        val core    = all
          .zip(entries)
          .collectFirst {
            case (p, e) if Files.exists(p.resolve("tui4s/Tui.tasty")) => e
          }
          .getOrElse(sys.error("no class path entry with tui4s/Tui.tasty"))
        (ctx, ctx.findSymbolsByClasspathEntry(core).toList)
    end reader

    private def scoped(s: TermOrTypeSymbol): Boolean = s.visibility match
        case Visibility.Public => false
        case _                 => true

    /** The classes of `pkg` (top-level and nested), and the members of its
      * top-level package objects (`<file>$package`).
      */
    private def classesOf(pkg: String)(using Context): List[ClassSymbol] =
        def nested(c: ClassSymbol): List[ClassSymbol] =
          c :: c.declarations
            .collect { case n: ClassSymbol => n }
            .flatMap(nested)
        reader._2
          .collect {
            case c: ClassSymbol if c.owner.displayFullName == pkg => c
          }
          .flatMap(nested)
          .distinct
    end classesOf

    /** The full names of the types that `t` names, shallow in the prefixes. */
    private def named(t: Any)(using Context): List[String] = t match
        case t: TypeRef         =>
          t.optSymbol.map(_.displayFullName).toList ++ named(t.prefix)
        case t: TermRef         => named(t.prefix)
        case t: AppliedType     => named(t.tycon) ++ t.args.flatMap(named)
        case t: MethodType      => t.paramTypes.flatMap(named) ++ named(t.resultType)
        case t: PolyType        =>
          t.paramTypeBounds.flatMap(named) ++ named(t.resultType)
        case t: TypeLambda      =>
          t.paramTypeBounds.flatMap(named) ++ named(t.resultType)
        case t: TypeBounds      => named(t.low) ++ named(t.high)
        case t: WildcardTypeArg => named(t.bounds)
        case t: ByNameType      => named(t.resultType)
        case t: RepeatedType    => named(t.elemType)
        case t: AnnotatedType   => named(t.typ)
        case t: AndType         => named(t.first) ++ named(t.second)
        case t: OrType          => named(t.first) ++ named(t.second)
        case t: FlexibleType    => named(t.nonNullableType)
        case t: TypeRefinement  => named(t.parent) ++ named(t.refinedBounds)
        case t: RefinedType     => named(t.parent)
        case _                  => Nil

    "every top-level definition of tui4s.internal is private[tui4s]" in {
      given Context           = reader._1
      val top                 = classesOf("tui4s.internal")
        .filter(_.owner.displayFullName == "tui4s.internal")
      // a top-level def, val or type lives in a synthetic `<file>$package`
      val (pkgObjects, types) =
        top.partition(_.name.toString.endsWith("$package$"))
      val members             = pkgObjects.flatMap(
        _.declarations.collect {
          case s: TermOrTypeSymbol
              if !s.isSynthetic && s.name.toString != "<init>" =>
            s
        },
      )
      val open                = (types.filterNot(_.isSynthetic) ++ members)
        .filterNot(scoped)
        .map(s => s"${s.displayFullName} (${s.visibility})")
        .distinct
      assert(types.size > 20, s"only ${types.size} classes in tui4s.internal")
      assert(open.isEmpty, "public in tui4s.internal:\n" + open.mkString("\n"))
    }

    "no public signature of tui4s, tui4s.spi or tui4s.testkit names a type of tui4s.internal" in {
      given Context     = reader._1
      val classes       = List("tui4s", "tui4s.spi", "tui4s.testkit")
        .flatMap(classesOf)
        .filterNot(scoped)
      val leaks         = for
          c <- classes
          s <- c.declarations.collect {
                 case s: TermOrTypeSymbol if !s.isSynthetic && !scoped(s) => s
               }
          t  = s match
                   case t: TermSymbol       => t.declaredType
                   case t: TypeMemberSymbol => t.typeDef
                   case other               => other
          n <- named(t) ++ c.parents.flatMap(named)
          if n.startsWith("tui4s.internal.")
      yield s"${s.displayFullName} names $n"
      val publicMembers = classes.flatMap(c =>
        c.declarations.collect {
          case s: TermSymbol if !s.isSynthetic && !scoped(s) => s
        },
      )
      assert(
        publicMembers.size > 30,
        s"only ${publicMembers.size} public members",
      )
      assert(
        leaks.distinct.isEmpty,
        "public signatures that name an internal type:\n" + leaks.distinct
          .mkString("\n"),
      )
    }
end PublicApiTest
