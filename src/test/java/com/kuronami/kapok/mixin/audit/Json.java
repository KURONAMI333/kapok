package com.kuronami.kapok.mixin.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The smallest JSON reader that can read a mixin config.
 *
 * <p>Gson is on the compile classpath because Minecraft brings it, but the audit
 * deliberately owns no dependency it does not need: everything else it reads is
 * bytecode, and the few files it parses here are hand written objects of
 * strings, arrays and one integer. A reader that fits on a screen is cheaper to
 * trust than a version of Gson that arrives through the game.
 *
 * <p>Objects become {@link LinkedHashMap} in file order, arrays become
 * {@link List}, numbers become {@link Integer} when they are whole and
 * {@link Double} otherwise. Nothing here is meant for input from outside the
 * repository.
 */
final class Json {

    private final String src;
    private int at;

    private Json(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.skipSpace();
        Object v = p.value();
        p.skipSpace();
        if (p.at != text.length()) {
            throw new IllegalArgumentException("trailing text at offset " + p.at);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected an object, got " + (v == null ? "null" : v.getClass()));
        }
        return (Map<String, Object>) v;
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        at++;
        skipSpace();
        if (peek() == '}') {
            at++;
            return out;
        }
        while (true) {
            skipSpace();
            String key = string();
            skipSpace();
            if (src.charAt(at++) != ':') {
                throw new IllegalArgumentException("expected ':' at offset " + (at - 1));
            }
            skipSpace();
            out.put(key, value());
            skipSpace();
            char c = src.charAt(at++);
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at offset " + (at - 1));
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        at++;
        skipSpace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            skipSpace();
            out.add(value());
            skipSpace();
            char c = src.charAt(at++);
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at offset " + (at - 1));
            }
        }
    }

    private String string() {
        if (src.charAt(at) != '"') {
            throw new IllegalArgumentException("expected a string at offset " + at);
        }
        at++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(at++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = src.charAt(at++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(src.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw new IllegalArgumentException("bad escape at offset " + (at - 1));
            }
        }
    }

    private Object number() {
        int start = at;
        while (at < src.length() && "+-.eE0123456789".indexOf(src.charAt(at)) >= 0) {
            at++;
        }
        String text = src.substring(start, at);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("expected a value at offset " + start);
        }
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            return Integer.valueOf(text);
        }
        return Double.valueOf(text);
    }

    private void expect(String word) {
        if (!src.startsWith(word, at)) {
            throw new IllegalArgumentException("expected '" + word + "' at offset " + at);
        }
        at += word.length();
    }

    private char peek() {
        if (at >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        return src.charAt(at);
    }

    private void skipSpace() {
        while (at < src.length() && Character.isWhitespace(src.charAt(at))) {
            at++;
        }
    }
}
