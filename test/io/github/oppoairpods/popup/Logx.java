package io.github.oppoairpods.popup;

/** Test-only no-op logger; the protocol tests run on a plain JVM. */
final class Logx {
    private Logx() {
    }

    static void i(String message) {
    }

    static void w(String message, Throwable error) {
    }

    static void e(String message, Throwable error) {
    }
}
