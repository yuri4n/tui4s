package surface

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import tastyquery.Contexts.Context
import tastyquery.Modifiers.Visibility
import tastyquery.Symbols.*
import tastyquery.jdk.ClasspathLoaders

/** Lists the public signatures of a package that name a type of package `kyo`, from TASTy (adapted from tui4s core's
  * SurfaceCheck; Java reflection cannot see Kyo's `A < S`, an opaque type that erases to Object).
  */
object SurfaceLeaks:
    // claude 2026-10-03: changed Leak: it also gives the Kyo names in the signature, so a test can allow the aliases of
    // kyo-ui data types by name (it allowed them by member name, which hid a Kyo type in an allowed member)
    final case class Leak(where: String, signature: String):
        override def toString: String = s"$where: $signature"

        /** The Kyo types that the signature names (`kyo.Maybe$package.Maybe` is `kyo.Maybe`). */
        def names: Seq[String] =
            "(?<![A-Za-z0-9_$.])kyo(?:\\.[A-Za-z0-9_$]+)+".r.findAllIn(signature).toList
                .map(n => if n.endsWith("kernel$package") then "kyo.<" else n.replaceAll("\\.[A-Za-z0-9_]+\\$package", ""))
                .distinct

        /** True when every Kyo name is one of `allowed` or a member of one (`kyo.Length.Px` under `kyo.Length`). */
        def onlyNames(allowed: Set[String]): Boolean =
            names.forall(n => allowed.exists(a => n == a || n.startsWith(a + ".")))

    private lazy val context: Context =
        val entries = System.getProperty("java.class.path", "").split(File.pathSeparator).toList
            .filter(_.nonEmpty).map(Path.of(_))
        val jrt = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules", "java.base")
        Context.initialize(ClasspathLoaders.read(jrt :: entries))

    def leaks(packages: Seq[String]): Seq[Leak] =
        given Context = context
        def visible(sym: TermOrTypeSymbol): Boolean =
            (sym.visibility == Visibility.Public || sym.visibility == Visibility.Protected) &&
                !(sym.isSynthetic && sym.name.toString.startsWith("inline"))
        def kyo(text: String): Boolean = "(^|[^A-Za-z0-9_$.])kyo\\.".r.findFirstIn(text).isDefined
        def visit(cls: ClassSymbol): Seq[Leak] =
            val name = cls.displayFullName
            cls.parents.map(_.showBasic).filter(kyo).map(Leak(s"$name (parent)", _)) ++
                cls.declarations.flatMap {
                    case c: ClassSymbol if visible(c)      => visit(c)
                    case t: TermSymbol if visible(t)       => Seq(Leak(s"$name.${t.name}", t.declaredType.showBasic)).filter(l => kyo(l.signature))
                    case t: TypeMemberSymbol if visible(t) => Seq(Leak(s"$name.${t.name}", t.declaredBounds.showBasic)).filter(l => kyo(l.signature))
                    case _                                 => Nil
                }
        packages.flatMap(p => context.findPackage(p).declarations.collect { case c: ClassSymbol if visible(c) => c }.flatMap(visit))
    end leaks
end SurfaceLeaks
