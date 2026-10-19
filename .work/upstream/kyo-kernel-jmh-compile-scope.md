# kyo-kernel 1.0.0-RC7 has JMH in compile scope

Draft for <https://github.com/getkyo/kyo/issues>. Written 2026-10-19 by the
tui4s maintainers.

## Summary

The POM of `io.getkyo:kyo-kernel_3:1.0.0-RC7` lists three JMH artifacts as
compile dependencies (no `<scope>`). So every user of Kyo gets JMH and its
dependencies on the runtime classpath, about 3.0 MB that no run loads.
The POMs of RC5 and RC6 have no JMH dependency, so this looks like a
regression of the RC7 build.

## The dependencies

From `kyo-kernel_3-1.0.0-RC7.pom`:

```xml
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-bytecode</artifactId>
    <version>1.37</version>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-reflection</artifactId>
    <version>1.37</version>
</dependency>
```

Through them come `jmh-generator-asm:1.37`, `commons-math3:3.6.1`, `asm:9.0`
and `jopt-simple:5.0.4`.

| Jar | Bytes |
| --- | ---: |
| jmh-core-1.37 | 552,986 |
| jmh-generator-bytecode-1.37 | 18,517 |
| jmh-generator-reflection-1.37 | 24,518 |
| jmh-generator-asm-1.37 | 31,641 |
| commons-math3-3.6.1 | 2,213,560 |
| asm-9.0 | 121,783 |
| jopt-simple-5.0.4 | 78,146 |
| **Total** | **3,041,151** |

## Evidence that nothing needs them at run time

- No class in `kyo-kernel_3-1.0.0-RC7.jar` refers to `org/openjdk/jmh`
  (a byte search of the extracted jar finds nothing).
- A class-load log (`-Xlog:class+load`) of two kyo-ui apps (a counter and a
  dashboard, about 4,100 to 4,500 classes each) shows no class from these
  seven jars.
- tui4s excludes `org.openjdk.jmh` from `kyo-core` and `kyo-ui`
  (`excludeAll(ExclusionRule("org.openjdk.jmh"))`): the build compiles, and
  the test suites of the library and of its example apps pass.

## Proposed fix

In the build of kyo-kernel, put the JMH dependencies in `Test` (or
`Provided`) scope, or move them to the benchmark project only. Users can
then drop their exclusions.
