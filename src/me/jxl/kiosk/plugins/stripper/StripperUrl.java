// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.stripper;

/**
 * Where to ask for the status.
 *
 * <p>The API is explicit that the port must not be hardcoded: the Stripper
 * answers on the same host and port the panel already loads dashboards from,
 * whatever that is. So the base is the panel's own Home Assistant URL, and
 * this only appends the path — the one case where guessing would produce a
 * plugin that works in one house and silently reports "not behind the
 * trimmer" in another.
 *
 * <p>An explicit override in settings wins, for the panel that reaches the
 * proxy somewhere other than where it fetches dashboards.
 */
public final class StripperUrl {

    public static final String PATH = "/stripper/client.json";

    private StripperUrl() {}

    /**
     * The status URL, or null when there is no usable base.
     *
     * <p>Null is a real answer: a panel with no Home Assistant URL set yet has
     * nothing to ask, and that is worth saying rather than fabricating
     * localhost and reporting it as unreachable.
     */
    public static String resolve(String override, String homeAssistantUrl) {
        final String base = pick(override, homeAssistantUrl);
        if (base == null) return null;
        return trimTrailingSlashes(base) + PATH;
    }

    private static String pick(String override, String homeAssistantUrl) {
        final String chosen = usable(override) ? override : homeAssistantUrl;
        if (!usable(chosen)) return null;
        final String trimmed = chosen.trim();
        // A base with no scheme is a configuration mistake rather than a
        // shorthand to be helpful about: guessing http for what is served
        // over https would fail in a way that reads as the proxy missing.
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null;
        return trimmed;
    }

    private static boolean usable(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Strips a path as well as trailing slashes: the configured URL often
     * points at a dashboard ({@code https://ha.example/office-tablet}), and
     * the status path is rooted at the host, not under whatever page the
     * panel happens to show.
     */
    private static String trimTrailingSlashes(String base) {
        final int scheme = base.indexOf("://");
        final int slash = base.indexOf('/', scheme + 3);
        final String origin = slash < 0 ? base : base.substring(0, slash);
        int end = origin.length();
        while (end > 0 && origin.charAt(end - 1) == '/') end--;
        return origin.substring(0, end);
    }
}
