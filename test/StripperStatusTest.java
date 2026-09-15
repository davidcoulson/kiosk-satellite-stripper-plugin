// SPDX-License-Identifier: Apache-2.0
import me.jxl.kiosk.plugins.stripper.StripperStatus;

/**
 * Parsing, against the exact payload the API documents plus the states it
 * warns about: nulls everywhere under `client`, and `connections: 0`, which
 * is a real state rather than an absence.
 */
public final class StripperStatusTest {

    /** The worked example from the API documentation, verbatim. */
    private static final String SAMPLE = "{"
        + "\"stripper\":{\"running\":true,\"version\":\"2026.09.15.21\",\"uptime_sec\":26},"
        + "\"trimming\":{\"entities\":true,\"by_dashboard\":true,\"registries\":true,"
        + "\"resources\":true,\"extra_modules\":true,\"services\":true,\"repairs\":true,"
        + "\"themes\":false,\"translations\":true,\"compress_websocket\":true},"
        + "\"client\":{\"ip\":\"10.2.3.42\",\"connections\":2,\"dashboard\":\"office-tablet\","
        + "\"attributed_via\":\"cookie\",\"entities_served\":88,"
        + "\"first_payload\":{\"entities\":88,\"bytes\":27726,\"ms_to_data\":391,\"drain_ms\":1},"
        + "\"traffic\":{\"from_ha_bytes\":637568,\"to_browser_bytes\":183599,"
        + "\"not_sent_bytes\":453969,\"not_sent_pct\":71,\"update_bytes_per_min\":null},"
        + "\"connected_sec\":21}}";

    public static void main(String[] args) {
        final StripperStatus ok = StripperStatus.trimmed(SAMPLE);
        assertTrue(ok.state == StripperStatus.State.TRIMMED, "200 is trimmed");
        assertTrue(ok.inPath() && !ok.idle(), "two connections is not idle");
        assertTrue("2026.09.15.21".equals(ok.version), "version");
        assertTrue(ok.entitiesServed == 88, "entities served");
        assertTrue(ok.notSentPct == 71, "not sent pct");
        assertTrue(ok.notSentBytes == 453969L, "not sent bytes");
        assertTrue(ok.firstPayloadMsToData == 391, "ms to data");
        assertTrue("office-tablet".equals(ok.dashboard), "dashboard");
        assertTrue(ok.trimsOn() == 9, "nine of ten trims on, themes off");

        // Documented as null until a connection is a minute old. It must stay
        // null: publishing 0 would turn "we decline to guess" into a reading.
        assertTrue(ok.updateBytesPerMin == null, "update rate stays null");

        // The tile shares a narrow row with "Validated" and elides after
        // roughly thirty characters, so it leads with the figure that
        // justifies the proxy -- what never crossed to this panel -- and
        // leaves the rest to the plugin's own page.
        final String tile = ok.tileText();
        assertTrue(tile.startsWith("71% dropped"), "tile leads with the drop: " + tile);
        assertTrue(tile.contains("88 entities"), "tile carries the entity count: " + tile);
        assertTrue(tile.length() <= 30, "tile short enough not to elide in the drawer: " + tile);
        assertTrue("on".equals(ok.tileLevel()), "trimming reads as on");

        // 404: Home Assistant answered, so there is no proxy in the path.
        final StripperStatus direct = StripperStatus.direct();
        assertTrue(direct.state == StripperStatus.State.DIRECT && !direct.inPath(), "404 is direct");
        assertTrue("off".equals(direct.tileLevel()), "direct reads as off");
        assertTrue("Disabled".equals(direct.tileText()), "direct wording");

        // 401 and 403 come from the proxy, so both are positive detections:
        // reporting either as "no proxy here" sends someone looking in the
        // wrong place. Neither carries figures.
        final StripperStatus unauthorised = StripperStatus.unauthorised();
        assertTrue(unauthorised.inPath(), "401 still means the proxy is in the path");
        assertTrue(!unauthorised.reporting(), "401 carries no figures");
        assertTrue("warn".equals(unauthorised.tileLevel()), "401 warns");
        assertTrue(unauthorised.tileText().contains("token"), "401 says what is missing");
        assertTrue(!unauthorised.tileText().equals(direct.tileText()), "401 is not worded as direct");

        final StripperStatus blocked = StripperStatus.blocked(
            "{\"error\":\"status requests are answered for local callers only\"}");
        assertTrue(blocked.inPath() && !blocked.reporting(), "403 is in path, no figures");
        assertTrue("warn".equals(blocked.tileLevel()), "403 warns");
        assertTrue(blocked.refusal != null && blocked.refusal.contains("local callers"),
            "403 keeps the proxy's own reason for the plugin page");
        assertTrue(!blocked.tileText().contains("local callers"),
            "the tile does not carry the proxy's sentence; the page does");

        // A refusal with no readable body is still a refusal.
        final StripperStatus bodyless = StripperStatus.blocked(null);
        assertTrue(bodyless.state == StripperStatus.State.BLOCKED && bodyless.refusal == null,
            "403 survives an unparseable body");
        assertTrue(StripperStatus.blocked("not json").refusal == null, "and a non-JSON one");

        // Nothing answered. A different thing to say than "not trimmed".
        final StripperStatus gone = StripperStatus.unreachable();
        assertTrue(!gone.inPath() && "warn".equals(gone.tileLevel()), "unreachable warns");
        assertTrue(!gone.tileText().equals(direct.tileText()), "unreachable reads differently to direct");

        // connections: 0 -- in the path, but holding no socket from here.
        final StripperStatus idle = StripperStatus.trimmed("{"
            + "\"stripper\":{\"running\":true,\"version\":\"2026.09.15.21\",\"uptime_sec\":3},"
            + "\"trimming\":{\"entities\":true},"
            + "\"client\":{\"ip\":\"10.2.3.42\",\"connections\":0,\"dashboard\":null,"
            + "\"attributed_via\":null,\"entities_served\":null,\"first_payload\":null,"
            + "\"traffic\":null,\"connected_sec\":null}}");
        assertTrue(idle.inPath() && idle.idle(), "zero connections is idle, still in path");
        assertTrue(idle.tileText().startsWith("On,"), "idle reads as on-but-waiting: " + idle.tileText());
        assertTrue("warn".equals(idle.tileLevel()), "idle warns rather than reading as on");
        assertTrue(idle.entitiesServed == null && idle.notSentPct == null, "nulls stay null");
        assertTrue(idle.trimming.get("entities"), "trimming still populated when idle");

        // Additive schema: unknown keys are ignored, not fatal, and unknown
        // trim flags are dropped rather than shown raw.
        final StripperStatus future = StripperStatus.trimmed("{"
            + "\"stripper\":{\"running\":true,\"version\":\"9999.01.01.01\",\"uptime_sec\":1,\"new\":5},"
            + "\"trimming\":{\"entities\":true,\"some_new_thing\":true},"
            + "\"client\":{\"connections\":1,\"entities_served\":4},"
            + "\"brand_new_section\":{\"x\":1}}");
        assertTrue(future.inPath(), "unknown keys do not break parsing");
        assertTrue(future.trimsOn() == 1, "unknown trim flags are not counted");
        assertTrue(future.entitiesServed == 4, "known fields still read");

        // A truncated or non-JSON body must not take the plugin down.
        boolean threw = false;
        try {
            StripperStatus.trimmed("{not json");
        } catch (Exception expected) {
            threw = true;
        }
        assertTrue(threw, "malformed JSON throws for the caller to handle");

        System.out.println("PASS: status parsing — documented sample, all six states, "
            + "401/403 as positive detections, connections:0 as its own state, "
            + "nulls preserved, additive schema tolerated.");
    }

    private static void assertTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
