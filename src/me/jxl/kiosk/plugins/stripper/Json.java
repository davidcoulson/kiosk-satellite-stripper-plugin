// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.stripper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader, enough for one diagnostics payload.
 *
 * <p>Android ships {@code org.json}, and using it would be the obvious
 * choice — except that it is a platform class, so every test of the parsing
 * would have to run on a device or against a stub. The payload this plugin
 * reads is a flat tree of objects, strings, numbers, booleans and nulls, and
 * a reader for that is small enough to own and test on the host, which is
 * where a schema surprise should be caught.
 *
 * <p>Numbers come back as {@link Double} throughout, the way JSON actually
 * defines them; callers narrow. Malformed input throws, and the caller treats
 * that as "the proxy answered with something unusable" rather than crashing.
 */
final class Json {

    private final String source;
    private int at;

    private Json(String source) {
        this.source = source;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        if (text == null) throw new IllegalArgumentException("No body");
        final Json reader = new Json(text);
        reader.skipSpace();
        final Object value = reader.readValue(0);
        reader.skipSpace();
        if (reader.at < reader.source.length()) throw new IllegalArgumentException("Trailing content");
        if (!(value instanceof Map)) throw new IllegalArgumentException("Not a JSON object");
        return (Map<String, Object>) value;
    }

    /** Depth-limited so a hostile or looping payload cannot blow the stack. */
    private Object readValue(int depth) {
        if (depth > 32) throw new IllegalArgumentException("Nested too deeply");
        skipSpace();
        if (at >= source.length()) throw new IllegalArgumentException("Unexpected end");
        final char c = source.charAt(at);
        switch (c) {
            case '{': return readObject(depth);
            case '[': return readArray(depth);
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject(int depth) {
        final Map<String, Object> out = new LinkedHashMap<>();
        at++; // {
        skipSpace();
        if (peek() == '}') { at++; return out; }
        while (true) {
            skipSpace();
            if (peek() != '"') throw new IllegalArgumentException("Expected a key");
            final String key = readString();
            skipSpace();
            if (peek() != ':') throw new IllegalArgumentException("Expected :");
            at++;
            out.put(key, readValue(depth + 1));
            skipSpace();
            final char next = peek();
            if (next == ',') { at++; continue; }
            if (next == '}') { at++; return out; }
            throw new IllegalArgumentException("Expected , or }");
        }
    }

    private List<Object> readArray(int depth) {
        final List<Object> out = new ArrayList<>();
        at++; // [
        skipSpace();
        if (peek() == ']') { at++; return out; }
        while (true) {
            out.add(readValue(depth + 1));
            skipSpace();
            final char next = peek();
            if (next == ',') { at++; continue; }
            if (next == ']') { at++; return out; }
            throw new IllegalArgumentException("Expected , or ]");
        }
    }

    private String readString() {
        at++; // opening quote
        final StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= source.length()) throw new IllegalArgumentException("Unterminated string");
            final char c = source.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (at >= source.length()) throw new IllegalArgumentException("Unterminated escape");
            final char esc = source.charAt(at++);
            switch (esc) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > source.length()) throw new IllegalArgumentException("Bad \\u");
                    out.append((char) Integer.parseInt(source.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default: throw new IllegalArgumentException("Bad escape \\" + esc);
            }
        }
    }

    private Double readNumber() {
        final int start = at;
        while (at < source.length() && "+-.eE0123456789".indexOf(source.charAt(at)) >= 0) at++;
        if (start == at) throw new IllegalArgumentException("Expected a value");
        try {
            return Double.valueOf(source.substring(start, at));
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("Bad number");
        }
    }

    private void expect(String literal) {
        if (!source.startsWith(literal, at)) throw new IllegalArgumentException("Expected " + literal);
        at += literal.length();
    }

    private char peek() {
        if (at >= source.length()) throw new IllegalArgumentException("Unexpected end");
        return source.charAt(at);
    }

    private void skipSpace() {
        while (at < source.length()) {
            final char c = source.charAt(at);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') at++;
            else break;
        }
    }
}
