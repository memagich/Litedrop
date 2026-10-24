package com.litedrop.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimalni JSON parser: objekti, nizovi, stringovi, brojevi, true/false/null. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) throws IOException {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("višak znakova");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws IOException {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IOException("JSON: očekivan objekt");
        return (Map<String, Object>) o;
    }

    public static long asLong(Object o, long def) {
        return o instanceof Number ? ((Number) o).longValue() : def;
    }

    public static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    /** Pretvara tekst u JSON string literal (s navodnicima). */
    public static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    private IOException err(String what) {
        return new IOException("JSON: " + what + " na poziciji " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private char peek() throws IOException {
        if (i >= s.length()) throw err("neočekivan kraj");
        return s.charAt(i);
    }

    private void expect(char c) throws IOException {
        if (peek() != c) throw err("očekivano '" + c + "'");
        i++;
    }

    private Object value() throws IOException {
        char c = peek();
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object() throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        expect('{');
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == ',') continue;
            if (c == '}') return m;
            throw err("očekivano ',' ili '}'");
        }
    }

    private List<Object> array() throws IOException {
        List<Object> l = new ArrayList<Object>();
        expect('[');
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ',') continue;
            if (c == ']') return l;
            throw err("očekivano ',' ili ']'");
        }
    }

    private String string() throws IOException {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("nezavršen string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw err("nezavršen escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    b.append(e);
                    break;
                case 'b':
                    b.append('\b');
                    break;
                case 'f':
                    b.append('\f');
                    break;
                case 'n':
                    b.append('\n');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'u':
                    if (i + 4 > s.length()) throw err("neispravan \\u");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw err("neispravan \\u");
                    }
                    i += 4;
                    break;
                default:
                    throw err("nepoznat escape");
            }
        }
    }

    private Object number() throws IOException {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw err("neočekivan znak");
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException ex) {
            throw err("neispravan broj");
        }
    }
}
