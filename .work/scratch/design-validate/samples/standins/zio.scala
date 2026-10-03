package zio

/** Stand-in, as in SPEC 6.3: core has no ZIO dependency; the NotForeign macro matches by full type name. */
final class ZIO[-R, +E, +A]
