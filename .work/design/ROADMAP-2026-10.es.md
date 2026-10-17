# Hoja de ruta de tui4s después de la fase Kyo (borrador para decidir, 2026-10-17)

Este archivo es la versión en español de `ROADMAP-2026-10.md`. Usa frases
cortas y voz activa, con el mismo espíritu que ASD-STE100. Los términos
técnicos quedan en inglés cuando es lo usual (Scope, Layer, fiber, store).
Nada aquí está decidido hasta que el usuario responda las preguntas de la
sección 5.

Los resultados crudos de la investigación (JSON y Markdown) están en
`.work/explore-2026-10-17/`.

<!-- claude 2026-10-17: created this section -->
## 1. Lo que pidió el usuario

1. Un ejemplo grande que muestre cómo estructurar una aplicación real.
2. La misma UI como aplicación web, por medio de kyo-ui.
3. Widgets suficientes para construir la mayoría de apps de terminal.
4. Un chat bot que use ChatGPT o Anthropic (API key, o suscripción si está
   permitido).
5. Un "Flutter pequeño": escribir la app una vez y generar una app de
   terminal y una app web. Las dos apps NO se comunican; cada una corre sola.
6. Un spike de componentes de un solo archivo o JSX para Scala ("scalax").
7. Una librería lista para publicar, con docs, ejemplos en vivo y benchmarks
   contra librerías de Scala y de otros lenguajes. La comparación debe poner
   el foco en lo compacto, lo expresivo, la corrección y el manejo de
   recursos.
8. Una librería de hooks como VueUse.
9. Una reescritura funcional de costo razonable del core, con las skills del
   usuario `functional-design` y `scala-functional-design` como reglas:
   código impuro solo por rendimiento y siempre detrás de un efecto.
10. Un arranque rápido y el mejor empaquetado posible (AOT de la JDK,
    GraalVM native-image), y algoritmos funcionales para el camino de cada
    frame.

<!-- claude 2026-10-17: created this section -->
## 2. Hechos que la investigación probó

### 2.1 Una fuente, dos plataformas (spikes, medido)

- El spike de Scala.js es la prueba que importa. Un solo archivo con la
  vista compiló en el build de la JVM y en el build de JS, sin
  sbt-crossproject. Un test headless de tui4s y un test en el navegador
  pasaron con esa misma vista. Esto es el modelo Flutter.
- Correspondencia con Flutter:

  | Flutter | tui4s |
  |---|---|
  | Árbol de widgets | un valor de kyo-ui |
  | Embedder de terminal | tui4s |
  | Embedder web | el runner DOM de kyo-ui en Scala.js |
  | Platform channels | traits de servicio con una implementación por plataforma, dadas con `Layer` |

- Diferencia honesta con Flutter: Flutter pinta los mismos píxeles en todas
  partes con su propio motor. Aquí cada plataforma dibuja de forma nativa:
  elementos DOM en la web y celdas en la terminal. Se parece más a React
  Native. Ganamos inputs reales y accesibilidad en la web, y no mantenemos un
  motor. Las dos apps no se ven idénticas. Un motor propio estilo Flutter
  costaría de 6.000 a 12.000 líneas; no lo recomiendo.
- Un primer spike en la JVM también corrió la misma vista en la terminal y
  en un navegador al mismo tiempo, con estado compartido en vivo. Eso NO es
  una meta (decisión del usuario, 2026-10-17). Solo probó que los dos
  renderers dibujan el mismo valor.
- Fuentes de los spikes: `.work/explore-2026-10-17/spike-js/` y
  `.work/explore-2026-10-17/spike-web/`.

### 2.2 Límites de la web (medido)

- `px` es una celda en tui4s y un píxel CSS en el navegador. Las vistas
  compartidas deben usar `em` o `pct`.
- Bundle de Scala.js: 33 MB (2,9 MB con gzip) por defecto. La base de datos
  de locales CLDR es el 61 %. Con `locales-minimal-en_us-db` baja a 7,9 MB
  (0,99 MB con gzip) y monta en 0,65 s.
- Bug de Kyo en JS: un input atado con `.value(SignalRef)` pierde teclas si
  llegan con 10 ms o menos entre una y otra.
- La app web es estática (sin servidor). Por eso desaparecen los problemas
  del modo server push: el WebSocket que acepta cualquier origen, el estado
  por sesión y la línea de log después de salir.

### 2.3 Lo que tui4s dibuja hoy

- tui4s respeta 31 de las 76 propiedades de Style de kyo-ui e ignora 45 en
  silencio (Overflow, Position, ZIndex, TextWrap, WhiteSpace, TextOverflow,
  Display, FlexWrap y otras). No hay scroll, ni overlay, ni wrap en filas, ni
  elipsis.
- El textarea es un campo de una línea. Los eventos de mouse se pierden. Svg
  y Chart muestran `[chart]`. Los nodos sin soporte nunca fallan y nunca
  avisan.
- De 28 widgets comunes de terminal, 5 funcionan hoy, 22 solo tienen un
  rodeo y 1 (textarea) no se puede construir.
- Funciones del core ordenadas por cuántos widgets desbloquean: scroll y
  recorte del viewport 12, wrap y elipsis 7, overlay y z-order 6, inputs
  nativos 5, caracteres anchos 4, input multilínea 3, señal del tamaño de la
  terminal 2, mouse 1.
- Consecuencia para "escribir una vez": mientras tui4s ignore estas
  propiedades, el mismo código funciona en la web y se rompe en la terminal.

### 2.4 Auditoría de calidad del código (93 sitios impuros)

- El veredicto "whiles por todos lados" no es correcto: hay unos 12 `while`
  reales, casi todos en archivos de borde. El veredicto "mal escrito" sí es
  correcto para la forma del código:
  - El código de borde está escrito como Java: unos 23 campos volátiles o
    nulables, cuatro copias de "corre cada paso y guarda el primer error" con
    tres políticas de catch distintas, y excepciones para resultados
    esperados.
  - El painter escribe en el buffer de jatatui mientras decide qué dibujar.
  - Runner y Mount usan java.util.concurrent donde Kyo ya tiene primitivas.
  - Los ejemplos enseñan idioms malos. El IO de archivos de Todo no se
    difiere (`Abort.catching` no suspende en RC7). TabsView esconde estado
    global.
  - Dos bugs latentes: un Panic se vuelve una salida limpia; un `foreach`
    dentro de un `tbody` probablemente nunca se actualiza.
- Clasificación: 29 sitios quedan como bordes reales, cada uno detrás de una
  sola costura de efecto; quedan 2 kernels (Canvas.write, Canvas.diff); unos
  50 se reemplazan y 13 se borran.
- La revisión contra las skills cambió el plan:
  - Una pila de liberación síncrona: cada adquisición registra su propia
    liberación (principio 10). Un hook de la JVM no puede correr los
    finalizers de un Scope de Kyo, porque `Finalizer.close` solo lanza una
    fiber.
  - Un solo hook de la JVM.
  - `kyo-test-prop` para tests de propiedades (existe para RC7; verificado
    en Maven Central).
  - Una taxonomía de errores con `NoStackTrace` y las formas públicas finales
    decididas ahora.
  - Menos proceso: solo scalafix DisableSyntax.
- Detalles: `.work/explore-2026-10-17/purity-audit.json` y
  `.work/explore-2026-10-17/skill-review.md`.

### 2.5 Proveedores de chat y sus reglas (citadas en el JSON)

- Anthropic: la API key está permitida. Los planes Max incluyen créditos de
  API mensuales para una API key. Un login de claude.ai o reusar sus tokens
  en una app de terceros está prohibido. Manejar el `claude -p` propio del
  usuario es zona gris: la nota del Agent SDK prohíbe que productos de
  terceros ofrezcan los límites de claude.ai.
- OpenAI: la API key está permitida. El Codex CLI del usuario, con sesión de
  ChatGPT (`codex app-server`), está permitido. "Sign in with ChatGPT" para
  apps locales open source es una preview (datos solo de resúmenes de
  búsqueda).
- Transporte: el cliente de kyo-http RC7 puede hacer streaming SSE sobre un
  POST y ya está en el classpath. Los SDKs oficiales de Java pesan 39 MB y
  69 MB más Kotlin, Jackson y OkHttp. Usamos clientes SSE delgados.
- En la web no hay servidor que guarde la key. Opciones: la key propia del
  usuario guardada solo en el navegador, un proxy pequeño, o solo el
  proveedor falso.

### 2.6 Otros hallazgos

- Hooks: la mayor parte de VueUse son de 1 a 3 líneas de Kyo nativo. Unos 13
  composables se ganan su lugar. Dos necesitan API nueva en el core (tamaño
  de la terminal como Signal; escrituras OSC 52).
- Trampa: tui4s espera cada handler en la fiber del runner. Un handler que
  espera una llamada de red congela la entrada. Las acciones que esperan
  deben hacer fork.
- Templates: un interpolador `ui"""..."""` con macros de Scala 3 es viable y
  sirve para cualquier runner. Los literales XML son un callejón sin salida
  (Scala 3 los quita).
- Docs: la rama `docs/docus` está vieja (es anterior a kyo-ui) y promete de
  más sobre los módulos ZIO y Cats. Copiamos solo `docs/`; no hacemos merge
  de esa rama.

### 2.7 Arranque y empaquetado (medición en curso)

- Hoy: de 1,24 a 1,30 s desde el arranque de la JVM hasta el primer frame
  (contenedor de 4 vCPU). Se cargan unas 4.090 clases: JDK 1.672, Scala 535,
  Kyo 905, jatatui 37, tui4s 243.
- kyo-http, kyo-net y kyo-ffi están en el classpath incluso para el counter
  (`modules/core/build.sbt:7-9`), porque kyo-ui depende de ellos.
- El empaquetado actual NO es el mejor: es un launcher de sbt-native-packager
  que arranca una JVM normal, sin caché AOT ni imagen nativa.
- Escalera de opciones, de menor a mayor riesgo:
  1. Caché AOT de la JDK 25 (JEP 483, 514 y 515): una corrida de
     entrenamiento y luego `-XX:AOTCache`. Sin cambios de código.
  2. Cargar menos clases: no inicializar lo que un counter no usa.
  3. Arranque concurrente: cargar crossterm y entrar a la terminal en un
     hilo mientras Kyo y kyo-ui inicializan y se arma el primer árbol en
     otro. La carga de clases es en gran parte secuencial, así que la
     ganancia tiene un techo.
  4. GraalVM native-image: arranque en decenas de milisegundos y menos
     memoria, si Kyo (sun.misc.Unsafe), el JNI de crossterm y la llamada FFM
     de isatty funcionan.
- Los algoritmos funcionales no aceleran el arranque (eso es carga de clases
  y empaquetado). Sí bajan el costo de cada frame: memoización del measure,
  relayout incremental, fusión de pasadas y un diff puro del plan de dibujo.
- Un workflow está midiendo la anatomía del arranque, la caché AOT y un
  intento de native-image, y está analizando el camino de cada frame con las
  skills. Los números entran en esta sección cuando termine.

<!-- claude 2026-10-17: created this section -->
## 3. El plan como una pila (orden de dependencias)

Cada capa necesita las capas de abajo.

| # | Capa | Contenido | Tamaño (estimado) |
|---|---|---|---|
| L0 | Reescritura funcional | Fases P0-P6 del plan revisado con las skills (lint, ganancias baratas, formas públicas, estado en Kyo, pila de liberación, ejemplos, painter puro, sinks) | unas 5.400 líneas cambiadas |
| L1 | Funciones del renderer | Scroll del viewport (Overflow de kyo-ui + scrollIntoView), wrap y elipsis, overlay y z-order, inputs nativos, textarea multilínea, caracteres anchos, tamaño de la terminal como Signal, Color.Var, un aviso por cada propiedad ignorada | de 3.000 a 5.000 líneas |
| L1b | Rendimiento por frame | Las mejoras de algoritmos funcionales del análisis en curso, con el código actual como oráculo y tests de propiedades | por definir |
| L2 | Librerías | `tui4s-widgets` (funciones de kyo-ui puras, portables) y `tui4s-use` (unos 13 composables, JVM y JS, sin depender del core) | de 3.000 a 5.000 líneas |
| L3 | App insignia | `examples.assistant`: chat con streaming, lista de conversaciones, ajustes, paleta de comandos, panel de logs. Sigue el plano de las skills: dominio puro, traits de servicio, intérpretes en `make`, stores con alcance de la app, wiring con Layer/Env, intérpretes en memoria para tests | de 2.000 a 3.000 líneas |
| L4 | Destino web | Un build de Scala.js independiente de la app insignia, desde el módulo compartido (archivos estáticos, sin servidor, sin enlace con la app de terminal). Los mismos builds dan las demos de los docs | de 500 a 1.000 líneas |
| L5 | Docs y benchmarks | Sitio Docus en main, casts del kit de PTY, demos JS, snippets compilados; programa de comparación (ver sección 4) | grande; en parte fuera de Scala |
| L5b | Empaquetado | Caché AOT en los launchers; native-image si el spike pasa | por definir |
| L6 | Release 0.1 | Checklist: Scaladoc en CI, matriz de sistemas operativos, changelog, SECURITY.md, plantilla, no publicar zio/cats vacíos, dry run, aprobación humana | pequeño |
| S1 | Spike: templates | Interpolador `ui"""..."""` en `experiments/scalax`, 5 días, después de L2 | spike |

Por qué L0 va primero: L1 cambia Layout, Paint y Runner. El scroll y el
overlay son funciones del momento de dibujar. Sobre un painter puro (P5) son
más baratas y se pueden probar con propiedades. El lint de P0 detiene código
impuro nuevo durante L1 y L2.

Estructura del build para el modelo Flutter:

- `shared`: JVM y Scala.js, depende solo de kyo-ui. Dominio, stores, vistas y
  traits de servicio.
- `tui`: solo JVM. El main de `TuiApp` y las implementaciones de la JVM
  (almacenamiento en archivo, cliente HTTP).
- `web`: solo Scala.js. Un main con `UI.runMount` y las implementaciones del
  navegador (localStorage, fetch).

<!-- claude 2026-10-17: created this section -->
## 4. Programa de comparación: dónde tui4s puede brillar

El titular de la comparación son las fortalezas. El rendimiento se publica
igual, de forma honesta, en una página propia.

| Eje | Qué medimos | Cómo |
|---|---|---|
| Compacto | Líneas, tokens y bytes con gzip por tarea, después del formateador de cada lenguaje | 7 tareas rosetta con especificación exacta: counter, form, todo con persistencia, dashboard, visor de logs de 100.000 líneas, chat con streaming, celdas |
| Expresivo | Conceptos distintos por tarea; costo de un cambio pedido después (diff de la petición de cambio) | conteo por script y por personas; la misma petición de cambio para todos |
| Corrección | Qué clases de bug son errores de compilación y cuáles explotan en ejecución | 13 clases de bug; un parche mutante revisado por implementación; resultado clasificado por la compilación, un crash o el oráculo |
| Manejo de recursos | Terminal restaurada al salir, al fallar, con SIGTERM y SIGINT, con un panic dentro de un handler y con un kill a mitad de frame; cero fibers, hilos, archivos o procesos hijos vivos después de la corrida; cancelar un streaming de LLM cierra el socket | una suite de resiliencia igual para todas las librerías, por el kit de PTY |
| Una fuente, dos plataformas | La misma vista en terminal y web, con los límites documentados | el módulo compartido y sus dos builds |
| Rendimiento (honesto) | Primer frame, latencia de la primera tecla y estable, memoria, CPU en reposo, tamaño | harness de PTY con marcas en nanosegundos; JVM normal, caché AOT y native-image por separado |

Para separar el costo de la JVM del costo de Kyo incluimos un par de la JVM
(TamboUI) y un programa de jatatui puro. Las implementaciones de los
competidores las revisan personas de cada ecosistema antes de publicar.

<!-- claude 2026-10-17: created this section -->
## 5. Decisiones (las responde el usuario)

Cada decisión tiene una recomendación (R).

- D1 Orden: L0 antes de L1. R: sí.
- D2 Detalles de la reescritura: (a) mantener las marcas `// claude <fecha>`,
  o mover la historia al texto del PR cuando una fase reescribe ese código;
  (b) aceptar las formas públicas finales (TuiError con NoStackTrace, Chunk en
  vez de Seq, Modifiers, CellColor, efectos de hooks más estrechos,
  `FrameStats.focus: Option[String]`); (c) P7 (modelo de datos tipado) queda
  fuera. R: (a) moverla al PR para el código reescrito, (b) sí, (c) sí.
- D3 App insignia: una sola app, el asistente de IA, dentro del proyecto
  `examples`. R: sí.
- D4 Proveedores: primero API keys de Anthropic y OpenAI y un proveedor falso
  con guion; después Codex app-server; el backend de Claude Code CLI nunca en
  código publicado. Secretos desde variables de entorno o un archivo 0600.
  R: sí.
- D5 Web: un build de Scala.js independiente desde un módulo compartido
  (crossProject JVM+JS, solo kyo-ui), sin enlace con la app de terminal. Los
  servicios de plataforma (almacenamiento, proveedor de chat) son traits con
  una implementación por plataforma, dadas con `Layer`. Requiere sbt-scalajs
  en `project/`, lo que marca todos los módulos como no publicados (aceptable
  antes de 0.1). Pregunta abierta: cómo obtiene la API key el build web del
  chat bot. R: la key propia del usuario, guardada solo en el navegador, más
  el proveedor falso para las demos de los docs.
- D6 Módulos: `tui4s-widgets` y `tui4s-use` como módulos publicados en este
  repo con sus propios tags, compilados para JVM y JS, sin depender del core.
  R: sí.
- D7 Alcance del core para 0.1: L1 como está listado, sin mouse (D-30 sigue).
  Tecla de nueva línea en el textarea: Alt-Enter y Ctrl-J, más Shift-Enter
  donde la terminal lo reporte. R: sí.
- D8 Aspecto web: tema web nativo, tema con look de terminal, o ambos.
  R: ambos, con un solo valor Theme que se vuelve variables CSS y una paleta
  de terminal.
- D9 Spike de templates: después de L2, huecos con `${...}`, Metals primero.
  R: sí.
- D10 Benchmarks: repo aparte `yuri4n/tui4s-bench` (más fácil recibir PRs de
  afuera); el titular es lo compacto, lo expresivo, la corrección y el manejo
  de recursos (sección 4); etapa 1 = Counter y Dashboard contra ratatui,
  Bubble Tea, Textual, Ink, TamboUI, layoutz y jatatui puro; publicar después
  de 0.1. R: sí. Abierto: quién pone una máquina dedicada.
- D11 Docs: copiar `docs/` a main en un PR nuevo; dominio y dueño del token
  de Vercel. Abierto: el dominio.
- D12 Release: versión 0.1.0-RC1 mientras Kyo sea RC; no publicar zio y cats
  vacíos; Windows experimental. R: sí.
- D13 Borradores para Kyo upstream (los publica el usuario): carrera de
  teclas en inputs controlados en JS; una unidad de largo en celdas o ch;
  acceso a scrollIntoView. R: sí, solo borradores.
- D14 Proceso: el lean mode sigue; un escéptico solo para código de
  concurrencia y de restauración de la terminal. R: sí.
- D15 Skills: quedan en main como skills del proyecto (hoy dicen
  "temporary"); corregir la fila de la skill de Scala que nombra
  `kyo-test-runner` en vez de `kyo-test-prop` para tests de propiedades.
  R: sí.
- D16 Empaquetado: caché AOT de la JDK 25 por defecto en los launchers;
  native-image como destino si el spike pasa sin parches frágiles; el
  arranque concurrente solo si la medición muestra una ganancia real.
  R: decidir con los números de la sección 2.7.
