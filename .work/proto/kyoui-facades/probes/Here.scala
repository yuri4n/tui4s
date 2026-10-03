package probes.mirror

/** P3: a tui4s-owned name for kyo.Frame. The macro of kyo.Frame runs at the call site of the user. */
opaque type Here = kyo.Frame

object Here:
    implicit inline def derive: Here = kyo.Frame.derive

def where(using h: Here): String = (h: kyo.Frame).show

def mirrorButton(using h: Here): kyo.UI.Ast.Button = kyo.UI.button(using h)
