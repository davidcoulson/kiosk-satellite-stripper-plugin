// SPDX-License-Identifier: Apache-2.0
import me.jxl.kiosk.plugins.stripper.StripperUrl;

/**
 * The URL is where this plugin is most likely to be quietly wrong: a bad
 * base does not throw, it returns "not behind the trimmer" on a panel that
 * very much is, and nobody would suspect the URL.
 */
public final class StripperUrlTest {
    public static void main(String[] args) {
        // The ordinary case: the panel's own HA URL, port and all. The API
        // is explicit that the port must not be assumed to be 9123.
        eq("https://home-iot.coulson.io/stripper/client.json",
            StripperUrl.resolve(null, "https://home-iot.coulson.io"));
        eq("http://10.2.3.6:9123/stripper/client.json",
            StripperUrl.resolve(null, "http://10.2.3.6:9123"));
        eq("http://10.2.3.6:8123/stripper/client.json",
            StripperUrl.resolve("", "http://10.2.3.6:8123"));

        // A configured URL usually points at a dashboard. The status path is
        // rooted at the host, so the path has to come off -- otherwise every
        // panel with a start URL reports "not behind the trimmer".
        eq("https://ha.example/stripper/client.json",
            StripperUrl.resolve(null, "https://ha.example/office-tablet/office-panel"));
        eq("https://ha.example/stripper/client.json",
            StripperUrl.resolve(null, "https://ha.example/"));
        eq("https://ha.example:8123/stripper/client.json",
            StripperUrl.resolve(null, "https://ha.example:8123///"));

        // An override wins, for the panel that reaches the proxy elsewhere.
        eq("http://proxy.lan:9123/stripper/client.json",
            StripperUrl.resolve("http://proxy.lan:9123", "https://ha.example"));
        eq("http://proxy.lan:9123/stripper/client.json",
            StripperUrl.resolve("  http://proxy.lan:9123  ", null));

        // No usable base is a real answer. Fabricating localhost here would
        // report "unreachable" for what is really "nothing configured".
        eq(null, StripperUrl.resolve(null, null));
        eq(null, StripperUrl.resolve("", ""));
        eq(null, StripperUrl.resolve(null, "   "));

        // A scheme-less base is a configuration mistake, not a shorthand:
        // guessing http for an https deployment fails as a missing proxy.
        eq(null, StripperUrl.resolve(null, "home-iot.coulson.io"));
        eq(null, StripperUrl.resolve("ha.example:9123", null));

        System.out.println("PASS: URL resolution — host-rooted path, dashboard paths stripped, "
            + "override precedence, and scheme-less or absent bases refused rather than guessed.");
    }

    private static void eq(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }
}
