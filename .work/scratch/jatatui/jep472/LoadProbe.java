import tui.crossterm.CrosstermJni;
import tui.crossterm.Duration;

public class LoadProbe {
  public static void main(String[] args) {
    System.out.println("java.version=" + System.getProperty("java.version") + " os.arch=" + System.getProperty("os.arch"));
    long t0 = System.nanoTime();
    CrosstermJni jni = new CrosstermJni();
    System.out.println("loaded CrosstermJni in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    try { System.out.println("terminalSize=" + jni.terminalSize()); }
    catch (Throwable e) { System.out.println("terminalSize threw " + e); }
    try { System.out.println("poll=" + jni.poll(new Duration(0, 0))); }
    catch (Throwable e) { System.out.println("poll threw " + e); }
    try { jni.enableRawMode(); System.out.println("enableRawMode ok"); }
    catch (Throwable e) { System.out.println("enableRawMode threw " + e); }
  }
}
