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
 * the answer rather than anything in the body. 404 is the only reply that
 * means "no proxy here" — Home Assistant has no such route. 401 and 403 are
 * refusals from the proxy itself and so are still positive detections: the
 * trimmer is in front of this panel, it just will not say what it is doing.
 * A transport failure means neither was reached — a network fault, which is
 * a different thing to say to someone than "not behind the trimmer".
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
        /** 401: in the path, but the token was missing or not accepted. */
        UNAUTHORISED,
        /** 403: in the path, but it does not answer callers from here. */
        BLOCKED,
        /** Nothing answered. A network problem, not a Stripper problem. */
        UNREACHABLE,
        /** Reached it, but it answered with something unusable. */
        ERROR,
    }

    public final State state;
    /** HTTP status when there was one, else 0. */
    public final int httpStatus;
    /**
     * The {@code error} field a 403 carries, saying why this caller was
     * refused. Null for every other state, and never shown raw in the tile —
     * it is the proxy's wording, not ours, and the tile has 80 characters.
     */
    public final String refusal;

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
        this(state, httpStatus, root, null);
    }

    private StripperStatus(State state, int httpStatus, Map<String, Object> root, String refusal) {
        this.state = state;
        this.httpStatus = httpStatus;
        this.refusal = refusal;

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

    /**
     * 401. The proxy answered, so it is in the path; it wants the panel's
     * Home Assistant token and did not get one it accepted.
     */
    public static StripperStatus unauthorised() {
        return new StripperStatus(State.UNAUTHORISED, 401, null);
    }

    /**
     * 403. The proxy answered and refused this caller — the request arrived
     * from the internet or through Cloudflare and the instance answers local
     * callers only. {@code body} is the reply, whose {@code error} field says
     * which; a body that will not parse is not worth failing over.
     */
    public static StripperStatus blocked(String body) {
        String reason = null;
        try {
            final Object value = Json.parseObject(body).get("error");
            if (value instanceof String && !((String) value).isEmpty()) reason = (String) value;
        } catch (RuntimeException unparseable) {
            // The status is the answer; the sentence explaining it is a bonus.
        }
        return new StripperStatus(State.BLOCKED, 403, null, reason);
    }

    public static StripperStatus unreachable() {
        return new StripperStatus(State.UNREACHABLE, 0, null);
    }

    public static StripperStatus error(int httpStatus) {
        return new StripperStatus(State.ERROR, httpStatus, null);
    }

    /**
     * Whether the proxy is in front of this panel at all. True for every
     * reply that came from the proxy, including the two refusals: a panel
     * that is told "not authorised" is still a panel behind the trimmer, and
     * reporting it as "no proxy here" would send someone looking in the
     * wrong place entirely.
     */
    public boolean inPath() {
        return state == State.TRIMMED || state == State.UNAUTHORISED || state == State.BLOCKED;
    }

    /** Whether the figures below are actually populated. */
    public boolean reporting() {
        return state == State.TRIMMED;
    }

    /**
     * Whether the proxy is in the path but holds no websocket from this
     * address. A real state, and not the same as not being behind the
     * trimmer: usually the panel has not opened its socket yet, or connects
     * from a different address than it fetches from.
     */
    public boolean idle() {
        return reporting() && connections != null && connections == 0;
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
     * The status tile's one line.
     *
     * <p>Written for where it is actually read: a row in the kiosk drawer
     * and Remote Admin's Overview, next to "Validated" and "Entities and BT
     * proxy", with room for roughly thirty characters before the panel
     * elides it. So this leads with the one number that justifies the proxy
     * existing — how much of Home Assistant's traffic never had to cross to
     * this panel — and leaves the byte totals, the dashboard attribution and
     * the trim flags to the plugin's own page, where there is room.
     *
     * <p>The percentage is this connection's own {@code not_sent}, which the
     * API is explicit is not the Stripper's headline "saved" figure, so it is
     * worded as traffic dropped for this panel rather than as a saving.
     */
    public String tileText() {
        switch (state) {
            case DIRECT:
                return "Disabled";
            case UNAUTHORISED:
                return "Needs an access token";
            case BLOCKED:
                return "Not answering this panel";
            case UNREACHABLE:
                return "Cannot reach Home Assistant";
            case ERROR:
                return "Unexpected reply" + (httpStatus > 0 ? " (HTTP " + httpStatus + ")" : "");
            default:
                break;
        }
        if (idle()) return "On, no connection yet";

        final StringBuilder text = new StringBuilder();
        if (notSentPct != null) text.append(notSentPct).append("% dropped");
        if (entitiesServed != null) {
            if (text.length() > 0) text.append(" · ");
            text.append(entitiesServed).append(" entities");
        }
        if (text.length() == 0) text.append("On, no figures yet");
        return text.length() <= 80 ? text.toString() : text.substring(0, 80);
    }

    /**
     * on when it is trimming and has the figures to show for it; off when
     * there is no proxy in the path, which is a settled state rather than a
     * fault; warn for everything in between — in the path but silent, idle,
     * or unreachable.
     */
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
