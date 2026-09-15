// SPDX-License-Identifier: Apache-2.0
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforces Kiosk Satellite's SDK 1 manifest contract locally, mirroring
 * PluginManifest.kt field for field.
 *
 * <h2>Why this exists</h2>
 *
 * 0.3.0 and 0.4.0 both shipped a `webviewPreset` description of 488
 * characters against a 400-character cap. The host rejects the whole
 * manifest with "Invalid description" and greys out **Trust and update**,
 * so both releases were uninstallable — and nothing caught it here,
 * because the build only checks that the JSON parses. The failure surfaced
 * on a panel, as a disabled button with a three-word error that names the
 * field but not the setting it came from or the limit it broke.
 *
 * Every limit below is copied from PluginManifest.kt. A release that fails
 * this test would fail on the panel; the point is to find out in a second
 * here rather than after publishing.
 */
public final class ManifestContractTest {
    public static void main(String[] args) throws Exception {
        String json = new String(Files.readAllBytes(manifestPath()), StandardCharsets.UTF_8);

        // Deliberately not a real JSON parser: this module has no JSON
        // dependency, and the checks that matter are per-field lengths and
        // patterns rather than structure. The manifest is generated from
        // the Java catalog and formatted by json.dump, so its shape is
        // stable enough to read with targeted extraction.
        text(json, "id", 64, "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");
        text(json, "name", 80, null);
        text(json, "version", 40, "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.-]+)?$");
        text(json, "entryClass", 200, "^[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+$");
        text(json, "description", 1000, null);
        text(json, "author", 120, null);
        text(json, "license", 120, null);

        List<String> settings = objectsIn(json, "\"settings\"");
        List<String> commands = objectsIn(json, "\"commands\"");
        assertTrue(settings.size() <= 20, "at most 20 settings (have " + settings.size() + ")");
        assertTrue(commands.size() <= 20, "at most 20 commands (have " + commands.size() + ")");

        Set<String> keys = new HashSet<>();
        for (String setting : settings) {
            String key = text(setting, "key", 64, "^[a-zA-Z][a-zA-Z0-9_]*$");
            assertTrue(keys.add(key), "duplicate setting key: " + key);
            text(setting, "title", 80, null);
            if (setting.contains("\"description\"")) text(setting, "description", 400, null);
            if (setting.contains("\"group\"")) text(setting, "group", 80, null);

            if (setting.contains("\"type\": \"select\"")) {
                List<String> options = stringsIn(setting, "\"options\"");
                assertTrue(options.size() >= 1 && options.size() <= 32,
                    key + ": a select takes 1..32 options, has " + options.size());
                Set<String> seen = new HashSet<>();
                for (String option : options) {
                    assertTrue(!option.trim().isEmpty() && option.length() <= 80,
                        key + ": option must be 1..80 chars: " + option);
                    assertTrue(seen.add(option), key + ": duplicate option: " + option);
                }
                String def = value(setting, "default");
                assertTrue(options.contains(def), key + ": default \"" + def + "\" is not one of its options");
            }
            if (setting.contains("\"type\": \"string\"")) {
                assertTrue(value(setting, "default").length() <= 512, key + ": default over 512 chars");
            }
        }

        Set<String> ids = new HashSet<>();
        for (String command : commands) {
            String id = text(command, "id", 64, "^[a-z][a-zA-Z0-9]*$");
            assertTrue(ids.add(id), "duplicate command ID: " + id);
            text(command, "title", 80, null);
        }

        System.out.println("PASS: manifest contract — " + settings.size() + "/20 settings, "
            + commands.size() + "/20 commands, every title, description, group, option and default "
            + "within PluginManifest.kt's limits.");
    }

    /** Checks one field's presence, non-blankness, length cap and (where
     *  the host has one) its pattern — the same four things text() and its
     *  callers check in PluginManifest.kt. */
    private static String text(String scope, String key, int limit, String pattern) {
        String v = value(scope, key);
        assertTrue(!v.trim().isEmpty(), key + " must not be blank");
        assertTrue(v.length() <= limit,
            key + " is " + v.length() + " chars, over the " + limit + " limit — the host rejects the "
                + "whole manifest with \"Invalid " + key + "\" and disables Trust and update: "
                + v.substring(0, Math.min(60, v.length())) + "…");
        if (pattern != null) {
            assertTrue(Pattern.compile(pattern).matcher(v).matches(), key + " does not match " + pattern + ": " + v);
        }
        return v;
    }

    private static String value(String scope, String key) {
        int at = scope.indexOf("\"" + key + "\":");
        assertTrue(at >= 0, "missing required field: " + key);
        int start = scope.indexOf('"', at + key.length() + 3);
        assertTrue(start >= 0, "unreadable value for " + key);
        return unescape(scope, start);
    }

    /** Reads one JSON string literal starting at the opening quote,
     *  honouring backslash escapes so a description containing \" is not
     *  truncated at the escape — which would under-count its length and
     *  defeat the check this test exists for. */
    private static String unescape(String s, int openQuote) {
        StringBuilder out = new StringBuilder();
        for (int i = openQuote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                        break;
                    default: out.append(next);
                }
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        throw new AssertionError("unterminated string in manifest");
    }

    /** The top-level objects of a named array, split on the indentation
     *  json.dump produces. */
    private static List<String> objectsIn(String json, String arrayKey) {
        int at = json.indexOf(arrayKey);
        if (at < 0) return new ArrayList<>();
        int start = json.indexOf('[', at);
        int depth = 0;
        int objectStart = -1;
        List<String> out = new ArrayList<>();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {                      // skip strings wholesale
                i = endOfString(json, i);
            } else if (c == '{') {
                if (depth == 0) objectStart = i;
                depth++;
            } else if (c == '}') {
                if (--depth == 0) out.add(json.substring(objectStart, i + 1));
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    private static List<String> stringsIn(String scope, String arrayKey) {
        int at = scope.indexOf(arrayKey);
        List<String> out = new ArrayList<>();
        if (at < 0) return out;
        int start = scope.indexOf('[', at);
        for (int i = start; i < scope.length(); i++) {
            char c = scope.charAt(i);
            if (c == ']') break;
            if (c == '"') {
                out.add(unescape(scope, i));
                i = endOfString(scope, i);
            }
        }
        return out;
    }

    private static int endOfString(String s, int openQuote) {
        for (int i = openQuote + 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') i++;
            else if (s.charAt(i) == '"') return i;
        }
        throw new AssertionError("unterminated string in manifest");
    }

    private static Path manifestPath() {
        Path here = Paths.get("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            Path candidate = p.resolve("kiosk-satellite-plugin.json");
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("could not locate kiosk-satellite-plugin.json from " + here);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
