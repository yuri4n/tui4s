package tui4s

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import tastyquery.Contexts.Context
import tastyquery.Modifiers.Visibility
import tastyquery.Symbols.*
import tastyquery.jdk.ClasspathLoaders

/** Finds `kyo.` types in the signatures of a public API, from the TASTy of the
  * classes (SPEC 6.5). Java reflection cannot do it: Kyo's `A < S` is an opaque
  * type that erases to `Object`.
  *
  * The facades run the same check on their own packages with this source
  * directory, not with `core % "test->test"`: the core tests have a stand-in
  * `zio.ZIO` class that would come before the real one. In the build of a
  * facade:
  * {{{
  * Test / unmanagedSourceDirectories +=
  *   (core / baseDirectory).value / "src" / "surface" / "scala",
  * libraryDependencies += "ch.epfl.scala" %% "tasty-query" % "1.9.0" % Test,
  * }}}
  */
object SurfaceCheck:
    /** A context over this JVM's class path without the test classes, and the
      * JDK's `java.base`.
      */
    lazy val context: Context =
        val entries =
          java.lang.System
            .getProperty("java.class.path", "")
            .split(File.pathSeparator)
            .toList
            .filter(e => e.nonEmpty && !e.endsWith("test-classes"))
            .map(Path.of(_))
        val jrt     = FileSystems
          .getFileSystem(URI.create("jrt:/"))
          .getPath("modules", "java.base")
        Context.initialize(ClasspathLoaders.read(jrt :: entries))
    end context

    /** One signature that names a Kyo type: "<owner>.<member>: <type>". */
    final case class Leak(where: String, signature: String):
        override def toString: String = s"$where: $signature"

    /** The public and protected members of every class and object in `packages`
      * (also nested classes) whose signature names a type of package `kyo`.
      * `exclude` gets the full name of a class or of a member
      * ("tui4s.TuiTest$.live") and leaves it out (with its nested classes).
      */
    def leaks(packages: Seq[String], exclude: String => Boolean): Seq[Leak] =
        given Context = context
        val out       = Vector.newBuilder[Leak]

        def visible(sym: TermOrTypeSymbol): Boolean = sym.visibility match
            case Visibility.Public | Visibility.Protected =>
              // An accessor that the compiler made for an inline method.
              !(sym.isSynthetic && sym.name.toString.startsWith("inline"))
            case _                                        => false

        def kyo(text: String): Boolean =
          text.contains("kyo.") && "(^|[^A-Za-z0-9_$.])kyo\\.".r
            .findFirstIn(text)
            .isDefined

        def check(where: String, text: String): Unit =
          if kyo(text) then out += Leak(where, text)

        def visit(cls: ClassSymbol): Unit =
            val name = cls.displayFullName
            if !exclude(name) then
                cls.parents.foreach(p => check(s"$name (parent)", p.showBasic))
                cls.declarations.foreach {
                  case c: ClassSymbol if visible(c)      => visit(c)
                  case t: TermSymbol if visible(t)       =>
                    val member = s"$name.${t.name}"
                    if !exclude(member) then
                        check(member, t.declaredType.showBasic)
                  case t: TypeMemberSymbol if visible(t) =>
                    val member = s"$name.${t.name}"
                    if !exclude(member) then
                        check(member, t.declaredBounds.showBasic)
                  case _                                 => ()
                }
            end if
        end visit

        packages.foreach { p =>
          context.findPackage(p).declarations.foreach {
            case c: ClassSymbol if visible(c) => visit(c)
            case _                            => ()
          }
        }
        out.result()
    end leaks
end SurfaceCheck
