package match2d.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON minimal, fara dependente: citeste in {@code Map} / {@code List} / {@code String} /
 * {@code Long} / {@code Double} / {@code Boolean} / {@code null} si scrie aceleasi tipuri, plus
 * tablouri de {@code int} si {@code double}.
 */
public final class Json {
    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.skip();
        Object value = p.value();
        p.skip();
        if (p.pos != p.src.length()) {
            throw p.error("text in plus dupa valoarea JSON");
        }
        return value;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON invalid la pozitia " + pos + ": " + what);
    }

    private void skip() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object value() {
        if (pos >= src.length()) {
            throw error("sfarsit neasteptat");
        }
        char c = src.charAt(pos);
        if (c == '{') {
            return object();
        }
        if (c == '[') {
            return array();
        }
        if (c == '"') {
            return string();
        }
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++;
        skip();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return out;
        }
        while (true) {
            skip();
            if (pos >= src.length() || src.charAt(pos) != '"') {
                throw error("astept o cheie");
            }
            String key = string();
            skip();
            if (pos >= src.length() || src.charAt(pos) != ':') {
                throw error("astept ':'");
            }
            pos++;
            skip();
            out.put(key, value());
            skip();
            if (pos >= src.length()) {
                throw error("obiect neinchis");
            }
            char c = src.charAt(pos++);
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw error("astept ',' sau '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        pos++;
        skip();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return out;
        }
        while (true) {
            skip();
            out.add(value());
            skip();
            if (pos >= src.length()) {
                throw error("lista neinchisa");
            }
            char c = src.charAt(pos++);
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw error("astept ',' sau ']'");
            }
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (true) {
            if (pos >= src.length()) {
                throw error("text neinchis");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw error("text neinchis");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw error("\\u incomplet");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Object number() {
        int start = pos;
        boolean real = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                real = true;
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw error("valoare necunoscuta");
        }
        String text = src.substring(start, pos);
        try {
            return real ? (Object) Double.valueOf(text) : (Object) Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw error("numar gresit: " + text);
        }
    }

    // ------------------------------------------------------------------ scriere
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(1 << 16);
        write(sb, value);
        return sb.toString();
    }

    public static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d).append(".0");
            } else {
                sb.append(d);
            }
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else if (v instanceof int[] ints) {
            sb.append('[');
            for (int i = 0; i < ints.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ints[i]);
            }
            sb.append(']');
        } else if (v instanceof double[] doubles) {
            sb.append('[');
            for (int i = 0; i < doubles.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, doubles[i]);
            }
            sb.append(']');
        } else {
            quote(sb, String.valueOf(v));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
