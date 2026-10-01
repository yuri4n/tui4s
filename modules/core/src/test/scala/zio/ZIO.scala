package zio

/** A stand-in for `zio.ZIO` (core has no ZIO dependency): the `NotForeign`
  * macro matches by full class name.
  */
final class ZIO[-R, +E, +A]
