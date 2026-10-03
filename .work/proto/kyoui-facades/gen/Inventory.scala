package gen

import KyoUiApi.{*, given}

/** Prints the inventory of the public kyo-ui API: factories, setters, and which
  * signatures mention the Kyo types that a ZIO / Cats user must not see.
  */
@main def inventory(): Unit =
    val factories  = uiMembers.filter(isElementFactory)
    val extensions = uiMembers.filter(_.sym.isExtensionMethod)
    val implicits  = uiMembers.filter(_.sym.isImplicit)
    val otherUi    = uiMembers.filterNot(m => isElementFactory(m) || m.sym.isExtensionMethod || m.sym.isImplicit)

    val perElement = elements.map(c => c -> setters(c))
    val pairs      = perElement.map(_._2.size).sum
    val unique     = perElement.flatMap(_._2).distinctBy(m => (m.owner, m.name, m.signature))

    def tokenTable(label: String, ms: List[Member]): Unit =
        println(s"  $label (${ms.size} signatures):")
        Tokens.all.foreach { t =>
            println(f"    ${t}%-10s ${ms.count(_.mentions(t))}%5d")
        }
        val effectful = ms.filter(m => m.mentions("<"))
        println(f"    ${"any Kyo effect type (<)"}%-24s ${effectful.size}%5d")
    end tokenTable

    println(s"kyo-ui public classes: ${allClasses.count(isPublic)}; public methods (all classes): ${allMembers.size}")
    println(s"kyo.UI companion: ${uiMembers.size} public methods")
    println(s"  element factories:      ${factories.size}")
    println(s"  Signal extensions:      ${extensions.size}  (${extensions.map(_.name).distinct.mkString(", ")})")
    println(s"  implicit conversions:   ${implicits.size}  (${implicits.map(_.name).distinct.mkString(", ")})")
    println(s"  other:                  ${otherUi.size}  (${otherUi.map(_.name).distinct.mkString(", ")})")
    println(s"element case classes: ${elements.size}")
    println(s"  setter overloads, unique (owner, name, signature): ${unique.size}")
    println(s"  setter names, unique: ${unique.map(_.name).distinct.size}")
    println(s"  (element, setter) pairs = forwarders if flattened: $pairs")
    println("  unique setters by declaring owner:")
    unique.groupBy(_.owner).toList.sortBy(-_._2.size).foreach { case (o, ms) =>
        println(f"    ${o}%-36s ${ms.size}%4d")
    }
    println("Token counts (a signature can mention several):")
    tokenTable("element factories", factories)
    tokenTable("unique setters", unique)
    tokenTable("Signal extensions + implicit conversions", extensions ++ implicits)
    tokenTable("all public methods of kyo-ui", allMembers)

    val handlerSetters = unique.filter(_.mentions("<"))
    println(s"Setters whose parameter is a Kyo effect (handlers): ${handlerSetters.size}")
    handlerSetters.groupBy(_.name).toList.sortBy(_._1).foreach { case (n, ms) =>
        println(s"    $n: ${ms.map(_.signature).mkString("  |  ")}")
    }
    val signalSetters = unique.filter(m => m.mentions("Signal") || m.mentions("SignalRef"))
    println(s"Setters that take a Signal / SignalRef: ${signalSetters.size}")
    signalSetters.foreach(m => println(s"    ${m.owner}.${m.name}: ${m.signature}"))
    println("Signal extensions / implicit conversions / other kyo.UI members:")
    (extensions ++ implicits ++ otherUi).foreach(m => println(s"    ${m.name}: ${m.signature}"))
    println("Element factories:")
    factories.foreach(m => println(s"    ${m.name}: ${m.signature}"))
end inventory
