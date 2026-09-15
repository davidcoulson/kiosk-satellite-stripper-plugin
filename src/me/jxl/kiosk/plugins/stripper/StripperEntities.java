// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.stripper;

import java.util.HashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The reading, as Home Assistant entities.
 *
 * <p>Null is published as null throughout, never as zero. The API is explicit
 * that every field under {@code client} can be null and that null means "not
 * known" — and a 0 published for an unknown would be averaged into a Home
 * Assistant long-term statistic as though someone had measured it. An unknown
 * entity state is honest and a graph gap is the correct picture.
 *
 * <p>{@code update_bytes_per_min} is null until a connection is a full minute
 * old, by the API's own design, because a rate extrapolated from four seconds
 * is an opening burst multiplied by fifteen. Publishing that as a number would
 * launder a disclaimer into a data point.
 */
final class StripperEntities {

    private StripperEntities() {}

    static void publish(PluginHost host, StripperStatus status) {
        // Whether the proxy is in front of this panel at all. The one field
        // worth a binary sensor: everything else is a measurement.
        binary(host, "in_path", "Stripper in path", "connectivity", status.inPath());

        text(host, "state", "Stripper state", stateText(status));
        text(host, "version", "Stripper version", status.version);
        text(host, "dashboard", "Stripper dashboard", status.dashboard);
        text(host, "attributed_via", "Stripper attribution", status.attributedVia);
        text(host, "trimming", "Stripper trimming", trimmingText(status));

        number(host, "entities_served", "Entities served", null, status.entitiesServed);
        number(host, "connections", "Stripper connections", null, status.connections);
        number(host, "not_sent_pct", "Traffic not sent", "%", status.notSentPct);

        // The three traffic totals in the order they happen: what Home
        // Assistant sent, what the proxy cut out of it, and what was left to
        // forward. from = trimmed + forwarded, and reading them in that order
        // makes that arithmetic obvious rather than something to work out
        // from three names that each described the same bytes differently.
        bytes(host, "from_ha_bytes", "Data from Home Assistant", status.fromHaBytes);
        bytes(host, "not_sent_bytes", "Data trimmed", status.notSentBytes);
        bytes(host, "to_browser_bytes", "Data forwarded", status.toBrowserBytes);

        rate(host, "update_bytes_per_min", "Update throughput", status.updateBytesPerMin);
        number(host, "first_payload_ms", "First entity data", "ms", status.firstPayloadMsToData);
        bytes(host, "first_payload_bytes", "First payload size", status.firstPayloadBytes);
        seconds(host, "uptime_sec", "Stripper uptime", status.uptimeSec);
    }

    /** Nothing is known: publish unknowns rather than leaving stale numbers. */
    static void publishUnknown(PluginHost host) {
        binary(host, "in_path", "Stripper in path", "connectivity", null);
        text(host, "state", "Stripper state", "No Home Assistant URL");
        for (final String key : new String[] {
            "version", "dashboard", "attributed_via", "trimming",
        }) {
            text(host, key, key, null);
        }
        for (final String key : new String[] {
            "entities_served", "connections", "not_sent_pct", "not_sent_bytes",
            "from_ha_bytes", "to_browser_bytes", "update_bytes_per_min",
            "first_payload_ms", "first_payload_bytes", "uptime_sec",
        }) {
            number(host, key, key, null, null);
        }
    }

    /**
     * The states as words, so an automation can branch on them without
     * reading two booleans and inferring the rest.
     *
     * <p>Every state the plugin distinguishes gets its own word. Folding the
     * two refusals into "error" was worse than useless: the tile read "Needs
     * an access token" while the entity an automation watches said "error",
     * so the panel told you what to do and Home Assistant told you something
     * had broken.
     */
    static String stateText(StripperStatus status) {
        switch (status.state) {
            case TRIMMED:
                return status.idle() ? "in path, idle" : "trimming";
            case DIRECT:
                return "direct";
            case UNAUTHORISED:
                return "needs a token";
            case BLOCKED:
                return "refused";
            case UNREACHABLE:
                return "unreachable";
            default:
                return "error";
        }
    }

    /**
     * The trim flags as a short list of the ones that are on.
     *
     * <p>A text sensor rather than one entity per flag: ten booleans that
     * change about never would be ten rows of noise in every entity list, and
     * the question people ask is "what is it cutting", not "is it cutting
     * translations specifically".
     */
    static String trimmingText(StripperStatus status) {
        // reporting(), not inPath(): a refusal proves the proxy is there but
        // carries no trim flags, and an empty list would read as "cutting
        // nothing" rather than "did not say".
        if (!status.reporting() || status.trimming.isEmpty()) return null;
        final StringBuilder on = new StringBuilder();
        for (final Map.Entry<String, Boolean> entry : status.trimming.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            if (on.length() > 0) on.append(", ");
            on.append(entry.getKey());
        }
        if (on.length() == 0) return "nothing";
        // The host caps a text state at 512 characters; ten short keys cannot
        // reach that, but the schema is additive and this is cheap insurance.
        return on.length() <= 512 ? on.toString() : on.substring(0, 512);
    }

    // ---- Host wrappers, each tolerant of an older host ----

    private static void number(PluginHost host, String key, String name, String unit, Number state) {
        number(host, key, name, unit, null, state);
    }

    /**
     * A byte total, published in bytes and declared as one.
     *
     * <p>Base units on purpose. A Home Assistant statistic whose unit slides
     * from KB to MB as the number grows is a broken statistic, so the figure
     * stays in bytes and the host rescales it for display -- 13483830 B reads
     * as 12.9 MB on the panel and in Remote Admin while the sensor underneath
     * never changes unit.
     */
    private static void bytes(PluginHost host, String key, String name, Number state) {
        number(host, key, name, "B", "data_size", state);
    }

    /** A byte rate. The denominator is part of the unit and survives rescaling. */
    private static void rate(PluginHost host, String key, String name, Number state) {
        number(host, key, name, "B/min", "data_rate", state);
    }

    /** A span of time in seconds, shown as `7m 8s` rather than `428 s`. */
    private static void seconds(PluginHost host, String key, String name, Number state) {
        number(host, key, name, "s", "duration", state);
    }

    private static void number(
            PluginHost host, String key, String name, String unit, String deviceClass, Number state) {
        try {
            final Map<String, Object> metadata = new HashMap<>();
            if (unit != null) metadata.put("unit", unit);
            if (deviceClass != null) metadata.put("deviceClass", deviceClass);
            metadata.put("accuracyDecimals", 0);
            host.publishSensor(key, name, metadata, state == null ? null : state.doubleValue());
        } catch (Throwable ignored) {
            // Entities unavailable on this host; the status tile still works.
        }
    }

    private static void text(PluginHost host, String key, String name, String state) {
        try {
            host.publishTextSensor(key, name, state);
        } catch (Throwable ignored) {
        }
    }

    private static void binary(PluginHost host, String key, String name, String deviceClass, Boolean state) {
        try {
            host.publishBinarySensor(key, name, deviceClass, state);
        } catch (Throwable ignored) {
        }
    }
}
