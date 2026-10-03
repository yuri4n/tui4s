// claude 2026-10-03: created the build of the integration prototype. It REUSES the source folders of the two prototypes
// (no copy, no edit) through `using files`, and adds only the glue (glue/), the mains (mains/) and the tests (test/).
//> using scala 3.9.0
//> using dep io.getkyo::kyo-ui:1.0.0-RC7
//> using dep io.getkyo::kyo-core:1.0.0-RC7
//> using dep io.getkyo::kyo-zio:1.0.0-RC7
//> using dep dev.zio::zio:2.1.26
//> using dep dev.zio::zio-streams:2.1.26
//> using dep org.typelevel::cats-effect:3.7.1
//> using dep co.fs2::fs2-core:3.12.2
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
//> using dep com.olvind.jatatui:jatatui-crossterm:0.31.0
//> using dep com.olvind.jatatui:crossterm:0.31.0
//> using test.dep org.scalameta::munit:1.3.6
//> using options -feature -deprecation -Wunused:imports
//> using javaOpt --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Xss16m -Xmx1g
//> using files ../kyoui-runner/src
//> using files ../kyoui-facades/shared
//> using files ../kyoui-facades/zio/generated ../kyoui-facades/zio/src ../kyoui-facades/zio/apps
//> using files ../kyoui-facades/cats/generated ../kyoui-facades/cats/src ../kyoui-facades/cats/apps
