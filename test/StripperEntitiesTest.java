// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.stripper;

/**
 * The words the entities carry.
 *
 * <p>In the plugin's own package on purpose: {@link StripperEntities} is
 * package-private, and making it public so a default-package test could
 * reach it would be the test dictating the design.
 *
 * <p>What this is really guarding is agreement between two surfaces. The
 * tile is read by a person standing at the panel and the state sensor is
 * read by automations, and 0.2.0 shipped with the refusals folded into
 * "error" on the sensor while the tile said "Needs an access token" -- the
 * panel telling you what to do and Home Assistant telling you something had
 * broken, about the same request.
 */
public final class StripperEntitiesTest {

    private static final String SAMPLE = "{"
        + "\"stripper\":{\"running\":true,\"version\":\"2026.09.15.24\",\"uptime_sec\":90},"
        + "\"trimming\":{\"entities\":true,\"themes\":false},"
        + "\"client\":{\"connections\":1,\"entities_served\":214,"
        + "\"traffic\":{\"not_sent_pct\":80}}}";

    public static void main(String[] args) {
        final StripperStatus trimming = StripperStatus.trimmed(SAMPLE);
        eq("trimming", StripperEntities.stateText(trimming));

        final StripperStatus idle = StripperStatus.trimmed("{"
            + "\"stripper\":{\"running\":true},\"trimming\":{\"entities\":true},"
            + "\"client\":{\"connections\":0}}");
        eq("in path, idle", StripperEntities.stateText(idle));

        eq("direct", StripperEntities.stateText(StripperStatus.direct()));
        eq("unreachable", StripperEntities.stateText(StripperStatus.unreachable()));

        // The two that used to read as "error".
        eq("needs a token", StripperEntities.stateText(StripperStatus.unauthorised()));
        eq("refused", StripperEntities.stateText(StripperStatus.blocked(null)));

        // A genuinely unexpected status is the only thing left called an error.
        eq("error", StripperEntities.stateText(StripperStatus.error(502)));

        // A refusal carries no trim flags. Null, not an empty list: "did not
        // say" and "cutting nothing" are different answers.
        eq(null, StripperEntities.trimmingText(StripperStatus.unauthorised()));
        eq(null, StripperEntities.trimmingText(StripperStatus.direct()));
        // One per line and in words: the comma-separated run of API keys
        // wrapped across three ragged lines on a settings row and had to be
        // read rather than scanned.
        final StripperStatus many = StripperStatus.trimmed("{"
            + "\"stripper\":{\"running\":true},"
            + "\"trimming\":{\"entities\":true,\"by_dashboard\":true,"
            + "\"extra_modules\":true,\"themes\":false,\"compress_websocket\":true},"
            + "\"client\":{\"connections\":1}}");
        eq("Entities\nBy dashboard\nExtra modules\nCompress websocket",
            StripperEntities.trimmingText(many));

        eq("Entities", StripperEntities.trimmingText(trimming));

        // Every flag off is an answer, and not the same as no reading.
        eq("nothing", StripperEntities.trimmingText(StripperStatus.trimmed("{"
            + "\"stripper\":{\"running\":true},\"trimming\":{\"entities\":false},"
            + "\"client\":{\"connections\":1}}")));

        System.out.println("PASS: entity states \u2014 every state its own word, "
            + "the refusals not reported as errors, and no trim list without a reading.");
    }

    private static void eq(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }
}
