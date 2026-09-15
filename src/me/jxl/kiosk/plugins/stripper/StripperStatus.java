// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.stripper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One reading of {@code /stripper/client.json}, and the four states a panel
 * can actually be in.
 *
 * <p>The endpoint's own contract drives the shape here. Reaching it at all is
 * the proof that the proxy is in front of this panel, so the HTTP status is
 * the answer rather than anything in the body: 200 means trimmed, 404 means
 * Home Assistant answered directly and has no such route, and a transport
 * failure means neither was reached — a network fault, which is a different
 * thing to say to someone than "not behind the trimmer".
 *
 * <p>Every field under {@code client} is nullable by contract, and null there
 * means "not known", never zero. That distinction survives into the entities:
 * a null is published as an unknown state rather than a 0 that would average
 * into a Home Assistant statistic as though it were a measurement.
 *
 * <p>The schema is additive by promise, so this reads the keys it knows and
 * ignores the rest rather than validating.
 */
public final class StripperStatus {

    public enum State {
        /** 200: the Stripper is running and in the path for this panel. */
        TRIMMED,
        /** 404: Home Assistant answered. No proxy in front of this panel. */
        DIRECT,
        /** Nothing answered. A network problem, not a Stripper problem. */
        UNREACHABLE,
        /** Reached it, but it answered with something unusable. */
        ERROR,
    }

    public final State state;
    /** HTTP status when there was one, else 0. */
    public final int httpStatus;

    public final boolean running;
    public final String version;
    public final Integer uptimeSec;

    /** Trim flags in declaration order, so a chip row reads consistently. */
    public final Map<String, Boolean> trimming;

    public final String ip;
    public final Integer connections;
    public final String dashboard;
    public final String attributedVia;
    public final Integer entitiesServed;
    public final Integer connectedSec;

    public final Integer firstPayloadEntities;
    public final Long firstPayloadBytes;
    public final Integer firstPayloadMsToData;
    public final Integer firstPayloadDrainMs;

    public final Long fromHaBytes;
    public final Long toBrowserBytes;
    public final Long notSentBytes;
    public final Integer notSentPct;
    public final Long updateBytesPerMin;

    private StripperStatus(State state, int httpStatus, Map<String, Object> root) {
        this.state = state;
        this.httpStatus = httpStatus;

        final Map<String, Object> stripper = obj(root, "stripper");
        this.running = Boolean.TRUE.equals(bool(stripper, "running"));
        this.version = str(stripper, "version");
        this.uptimeSec = integer(stripper, "uptime_sec");

        final Map<String, Object> trims = obj(root, "trimming");
        final Map<String, Boolean> flags = new LinkedHashMap<>();
        if (trims != null) {
            for (final String key : KNOWN_TRIMS) {
                final Boolean value = bool(trims, key);
                if (value != null) flags.put(key, value);
            }
        }
        this.trimming = Collections.unmodifiableMap(flags);

        final Map<String, Object> client = obj(root, "client");
        this.ip = str(client, "ip");
        this.connections = integer(client, "connections");
        this.dashboard = str(client, "dashboard");
        this.attributedVia = str(client, "attributed_via");
        this.entitiesServed = integer(client, "entities_served");
        this.connectedSec = integer(client, "connected_sec");

        final Map<String, Object> first = obj(client, "first_payload");
        this.firstPayloadEntities = integer(first, "entities");
        this.firstPayloadBytes = number(first, "bytes");
        this.firstPayloadMsToData = integer(first, "ms_to_data");
        this.firstPayloadDrainMs = integer(first, "drain_ms");

        final Map<String, Object> traffic = obj(client, "traffic");
        this.fromHaBytes = number(traffic, "from_ha_bytes");
        this.toBrowserBytes = number(traffic, "to_browser_bytes");
        this.notSentBytes = number(traffic, "not_sent_bytes");
        this.notSentPct = integer(traffic, "not_sent_pct");
        this.updateBytesPerMin = number(traffic, "update_bytes_per_min");
    }

    /**
     * The trim flags this build knows how to name, in the order the API
     * documents them. Unknown flags are dropped rather than shown raw: a chip
     * reading {@code some_new_thing} is worse than no chip.
     */
    private static final String[] KNOWN_TRIMS = {
        "entities", "by_dashboard", "registries", "resources", "extra_modules",
        "services", "repairs", "themes", "translations", "compress_websocket",
    };

    public static StripperStatus trimmed(String body) {
        return new StripperStatus(State.TRIMMED, 200, Json.parseObject(body));
    }

    public static StripperStatus direct() {
        return new StripperStatus(State.DIRECT, 404, null);
    }

    public static StripperStatus unreachable() {
        return new StripperStatus(State.UNREACHABLE, 0, null);
    }

    public static StripperStatus error(int httpStatus) {
        return new StripperStatus(State.ERROR, httpStatus, null);
    }

    public boolean inPath() {
        return state == State.TRIMMED;
    }

    /**
     * Whether the proxy is in the path but holds no websocket from this
     * address. A real state, and not the same as not being behind the
     * trimmer: usually the panel has not opened its socket yet, or connects
     * from a different address than it fetches from.
     */
    public boolean idle() {
        return inPath() && connections != null && connections == 0;
    }

    /** The trim flags that are on, for a short chip row. */
    public int trimsOn() {
        int on = 0;
        for (final Boolean value : trimming.values()) {
            if (Boolean.TRUE.equals(value)) on++;
        }
        return on;
    }

    /**
     * The status tile's one line, inside the host's 80 character limit.
     *
     * <p>Leads with the number people actually want. The percentage is the
     * connection's own {@code not_sent}, which the API is explicit is not the
     * Stripper's headline "saved" figure, so it is worded as what was not
     * sent to this panel rather than as a saving.
     */
    public String tileText() {
        switch (state) {
            case DIRECT:
                return "Not behind the trimmer";
            case UNREACHABLE:
                return "Cannot reach the panel's Home Assistant URL";
            case ERROR:
                return "Unexpected reply" + (httpStatus > 0 ? " (HTTP " + httpStatus + ")" : "");
            default:
                break;
        }
        if (idle()) return "In the path, no websocket from this panel yet";

        final StringBuilder text = new StringBuilder();
        if (entitiesServed != null) text.append(entitiesServed).append(" entities");
        if (notSentPct != null) {
            if (text.length() > 0) text.append(" · ");
            text.append(notSentPct).append("% not sent");
        }
        if (dashboard != null && !dashboard.isEmpty()) {
            if (text.length() > 0) text.append(" · ");
            text.append(dashboard);
        }
        if (text.length() == 0) text.append("Trimming, no figures yet");
        return text.length() <= 80 ? text.toString() : text.substring(0, 80);
    }

    /** on when it is trimming, warn when it is in the path but idle or odd. */
    public String tileLevel() {
        switch (state) {
            case TRIMMED:
                return idle() ? "warn" : "on";
            case DIRECT:
                return "off";
            default:
                return "warn";
        }
    }

    // ---- JSON helpers. Absent, null and wrong-typed all read as null. ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> parent, String key) {
        if (parent == null) return null;
        final Object value = parent.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static String str(Map<String, Object> parent, String key) {
        if (parent == null) return null;
        final Object value = parent.get(key);
        if (!(value instanceof String)) return null;
        final String text = (String) value;
        return text.isEmpty() ? null : text;
    }

    private static Boolean bool(Map<String, Object> parent, String key) {
        if (parent == null) return null;
        final Object value = parent.get(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static Integer integer(Map<String, Object> parent, String key) {
        final Long value = number(parent, key);
        return value == null ? null : value.intValue();
    }

    private static Long number(Map<String, Object> parent, String key) {
        if (parent == null) return null;
        final Object value = parent.get(key);
        return value instanceof Number ? ((Number) value).longValue() : null;
    }
}
