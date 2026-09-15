// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.stripper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Reports what the HA WebSocket Stripper is doing in front of this panel.
 *
 * <p>Read-only by design. The API's own warning is that the endpoint is
 * unauthenticated diagnostics and must not be built on: nothing here gates
 * behaviour on what it finds, and nothing is relayed anywhere except into
 * this panel's own entities, which already live on the same network.
 *
 * <p>The API also asks callers not to poll it — it takes a stats snapshot per
 * call. A plugin publishing Home Assistant entities cannot honour that
 * literally, since an entity nobody refreshes is an entity that lies. The
 * compromise is a deliberately slow default (two minutes), 0 to disable it
 * entirely, and a manual refresh command for when someone is actually
 * looking.
 */
public final class StripperPlugin implements KioskPlugin {

    private static final String TILE = "stripper";
    private static final String TILE_TITLE = "WebSocket Stripper";

    private final AtomicBoolean alive = new AtomicBoolean();
    private PluginHost host;
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> refresh;

    private Map<String, Object> settings = Collections.emptyMap();
    /** Last Home Assistant URL the host told us about. */
    private volatile String dashboardBase;

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.settings = new HashMap<>(settings);
        alive.set(true);
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "stripper-plugin");
            thread.setDaemon(true);
            return thread;
        });
        tile("", "reading…");
        schedule();
        submit(this::readNow);
    }

    @Override
    public void configure(Map<String, Object> settings) {
        this.settings = new HashMap<>(settings);
        schedule();
        submit(this::readNow);
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("refresh".equals(command)) submit(this::readNow);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        // Nothing subscribed: the URL is asked for per read rather than
        // tracked, so there is no event worth waking for.
    }

    @Override
    public void stop() throws Exception {
        alive.set(false);
        if (refresh != null) refresh.cancel(false);
        if (worker != null) {
            worker.shutdownNow();
            if (!worker.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Stripper worker did not stop");
            }
        }
    }

    // ---- Scheduling ----

    private void schedule() {
        if (refresh != null) refresh.cancel(false);
        refresh = null;
        final long seconds = number("refreshSeconds", 120);
        if (seconds <= 0 || worker == null) return;
        refresh = worker.scheduleWithFixedDelay(
            () -> safe(this::readNow), seconds, seconds, TimeUnit.SECONDS);
    }

    private void submit(Runnable task) {
        if (alive.get() && worker != null) worker.execute(() -> safe(task));
    }

    private void safe(Runnable task) {
        try {
            if (alive.get()) task.run();
        } catch (Throwable error) {
            if (alive.get()) host.log("stripper: " + error);
        }
    }

    // ---- Reading ----

    /**
     * Ask the host where Home Assistant is, then read the status.
     *
     * <p>The base is fetched per read rather than cached at start: a panel
     * can be repointed at another Home Assistant while running, and a plugin
     * still reporting on the old one would be worse than one reporting
     * nothing.
     */
    private void readNow() {
        final String override = text("baseUrl");
        if (override != null && !override.trim().isEmpty()) {
            publish(fetch(StripperUrl.resolve(override, null)));
            return;
        }
        host.executeCommand("getDashboardState", Collections.emptyMap(), (ok, data, error) -> {
            String base = null;
            if (ok && data instanceof Map) {
                final Object url = ((Map<?, ?>) data).get("homeAssistantUrl");
                if (url != null) base = String.valueOf(url);
            }
            dashboardBase = base;
            final String resolved = StripperUrl.resolve(null, base);
            submit(() -> publish(fetch(resolved)));
        });
    }

    /** One GET, with the configured timeout on both connect and read. */
    private StripperStatus fetch(String url) {
        if (url == null) return null;
        final int timeout = (int) number("timeoutMs", 2000);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-store");
            connection.setRequestProperty("Accept", "application/json");
            final String token = text("accessToken");
            if (token != null && !token.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }

            final int status = connection.getResponseCode();
            if (status == 404) return StripperStatus.direct();
            if (status == 401) return StripperStatus.unauthorised();
            if (status == 403) return StripperStatus.blocked(readError(connection));
            if (status != 200) return StripperStatus.error(status);
            try (InputStream stream = connection.getInputStream()) {
                return StripperStatus.trimmed(read(stream));
            }
        } catch (java.io.IOException unreachable) {
            return StripperStatus.unreachable();
        } catch (Exception malformed) {
            return StripperStatus.error(0);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * The body of a refusal, which lives on the error stream rather than the
     * input stream. Best effort: a 403 with no readable body is still a 403.
     */
    private static String readError(HttpURLConnection connection) {
        try (InputStream stream = connection.getErrorStream()) {
            return stream == null ? null : read(stream);
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    /** Bounded read: a diagnostics payload is small, and a proxy that answers
     *  with something enormous should not be able to exhaust the panel. */
    private static String read(InputStream stream) throws java.io.IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[4096];
        int total = 0;
        int count;
        while ((count = stream.read(chunk)) > 0) {
            total += count;
            if (total > 256 * 1024) throw new java.io.IOException("Status payload too large");
            buffer.write(chunk, 0, count);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- Publishing ----

    private void publish(StripperStatus status) {
        if (!alive.get()) return;
        if (status == null) {
            tile("warn", "No Home Assistant URL configured on this panel");
            StripperEntities.publishUnknown(host);
            host.status("No Home Assistant URL to ask.", true);
            return;
        }
        tile(status.tileLevel(), status.tileText());
        StripperEntities.publish(host, status);
        host.status(statusLine(status),
            status.state == StripperStatus.State.UNREACHABLE
                || status.state == StripperStatus.State.UNAUTHORISED
                || status.state == StripperStatus.State.BLOCKED);
    }

    private String statusLine(StripperStatus status) {
        switch (status.state) {
            case TRIMMED:
                return "Trimmed by WebSocket Stripper"
                    + (status.version != null ? " " + status.version : "")
                    + " — " + status.tileText()
                    + (status.dashboard != null ? " · " + status.dashboard : "");
            case DIRECT:
                return "Talking to Home Assistant directly; no Stripper in the path.";
            case UNAUTHORISED:
                return "The Stripper is in the path but wants this panel's Home Assistant "
                    + "access token. Paste one into the plugin's Access token setting.";
            case BLOCKED:
                return "The Stripper is in the path but will not answer this panel"
                    + (status.refusal != null ? ": " + status.refusal : ".");
            case UNREACHABLE:
                return "Could not reach " + (dashboardBase != null ? dashboardBase : "Home Assistant") + ".";
            default:
                return "The Stripper answered with something unexpected.";
        }
    }

    private void tile(String level, String text) {
        try {
            host.publishStatusTile(TILE, TILE_TITLE, level, text);
        } catch (Throwable older) {
            // A host without status tiles. Entities and the status line are
            // unaffected; only the Overview box is missing.
        }
    }

    // ---- Settings ----

    private long number(String key, long fallback) {
        final Object value = settings.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private String text(String key) {
        final Object value = settings.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
