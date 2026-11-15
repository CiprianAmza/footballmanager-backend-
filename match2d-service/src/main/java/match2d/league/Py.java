package match2d.league;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Regulile lui Python de care depinde liga, ca varianta Java sa dea exact ce da {@code league_tv.py}:
 * adevarul unei valori, egalitatea numerelor, compararea textelor, {@code round}, {@code repr} pe
 * numere reale, {@code int()} / {@code float()} pe texte si {@code json.dumps}.
 */
final class Py {
    private Py() {
    }

    // ------------------------------------------------------------------ valori
    /** {@code bool(v)}. */
    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Double || v instanceof Float) {
            return ((Number) v).doubleValue() != 0.0;
        }
        if (v instanceof BigInteger bi) {
            return bi.signum() != 0;
        }
        if (v instanceof Number n) {
            return n.longValue() != 0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** Numar in sensul lui Python ({@code bool} e si el {@code int}). */
    static boolean isNum(Object v) {
        return v instanceof Number || v instanceof Boolean;
    }

    static double num(Object v) {
        if (v instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        return ((Number) v).doubleValue();
    }

    private static boolean integral(Object v) {
        return v instanceof Boolean || v instanceof Long || v instanceof Integer || v instanceof Short
                || v instanceof Byte;
    }

    private static long lng(Object v) {
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return ((Number) v).longValue();
    }

    /** {@code a == b}. */
    static boolean eq(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (isNum(a) && isNum(b)) {
            if (integral(a) && integral(b)) {
                return lng(a) == lng(b);
            }
            return num(a) == num(b);
        }
        if (a instanceof String || b instanceof String) {
            return a.equals(b);
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            Iterator<?> ia = la.iterator();
            Iterator<?> ib = lb.iterator();
            while (ia.hasNext()) {
                if (!eq(ia.next(), ib.next())) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey()) || !eq(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /** {@code m.get(k, default)}: valoarea, chiar {@code None}, daca cheia exista. */
    static Object get(Map<?, ?> m, Object k, Object fallback) {
        return m.containsKey(k) ? m.get(k) : fallback;
    }

    /** Compararea textelor ca in Python: pe puncte de cod, nu pe unitati UTF-16. */
    static int cmp(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    /** Compararea a doua numere cu {@code <} si {@code >} (0.0 si -0.0 sunt egale, ca in Python). */
    static int cmp(double a, double b) {
        return a < b ? -1 : a > b ? 1 : 0;
    }

    /** {@code min(a, b)} din Python: {@code b} doar daca e strict mai mic. */
    static double min(double a, double b) {
        return b < a ? b : a;
    }

    /** {@code max(a, b)} din Python: {@code b} doar daca e strict mai mare. */
    static double max(double a, double b) {
        return b > a ? b : a;
    }

    /** {@code round(x, nd)} pe un numar real: rotunjirea exacta, jumatatea la par. */
    static double round(double x, int nd) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        double r = new BigDecimal(x).setScale(nd, RoundingMode.HALF_EVEN).doubleValue();
        return r == 0.0 ? Math.copySign(0.0, x) : r;
    }

    /** {@code str(x)}. */
    static String str(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d)) {
                return "nan";
            }
            if (Double.isInfinite(d)) {
                return d > 0 ? "inf" : "-inf";
            }
            return repr(d);
        }
        return String.valueOf(v);
    }

    /** {@code repr(x)} al unui numar real finit: cele mai putine cifre, in forma lui Python. */
    static String repr(double d) {
        if (d == 0.0) {
            return (1.0 / d < 0) ? "-0.0" : "0.0";
        }
        String sign = d < 0 ? "-" : "";
        String s = Double.toString(Math.abs(d));
        int e = 0;
        int ei = s.indexOf('E');
        if (ei >= 0) {
            e = Integer.parseInt(s.substring(ei + 1));
            s = s.substring(0, ei);
        }
        int dot = s.indexOf('.');
        String a = dot >= 0 ? s.substring(0, dot) : s;
        String b = dot >= 0 ? s.substring(dot + 1) : "";
        String digits = a + b;
        int decpt = a.length() + e;         // valoarea = 0.cifre x 10^decpt
        int lead = 0;
        while (lead < digits.length() - 1 && digits.charAt(lead) == '0') {
            lead++;
        }
        digits = digits.substring(lead);
        decpt -= lead;
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);
        int n = digits.length();
        StringBuilder out = new StringBuilder(sign);
        if (decpt <= -4 || decpt > 16) {
            out.append(digits.charAt(0));
            if (n > 1) {
                out.append('.').append(digits, 1, n);
            }
            int exp = decpt - 1;
            out.append('e').append(exp < 0 ? '-' : '+');
            int ax = Math.abs(exp);
            if (ax < 10) {
                out.append('0');
            }
            out.append(ax);
        } else if (decpt <= 0) {
            out.append("0.");
            for (int i = 0; i < -decpt; i++) {
                out.append('0');
            }
            out.append(digits);
        } else if (decpt >= n) {
            out.append(digits);
            for (int i = n; i < decpt; i++) {
                out.append('0');
            }
            out.append(".0");
        } else {
            out.append(digits, 0, decpt).append('.').append(digits, decpt, n);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ int() si float() pe texte
    private static final Pattern INT = Pattern.compile("[+-]?\\d+(?:_\\d+)*", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String DIG = "\\d(?:_?\\d)*";
    private static final Pattern FLOAT = Pattern.compile("[+-]?(?:(?:(?:" + DIG + ")?\\." + DIG + "|" + DIG
            + "\\.?)(?:[eE][+-]?" + DIG + ")?|(?i:inf|infinity|nan))", Pattern.UNICODE_CHARACTER_CLASS);

    private static String ascii(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == '_') {
                continue;
            }
            if (Character.isDigit(c)) {
                sb.append((char) ('0' + Character.digit(c, 10)));
            } else {
                sb.appendCodePoint(c);
            }
        }
        return sb.toString();
    }

    /** {@code int(text)}; {@code null} daca Python ar da {@code ValueError}. */
    static BigInteger parseInt(String text) {
        String s = text.strip();
        if (!INT.matcher(s).matches()) {
            return null;
        }
        return new BigInteger(ascii(s));
    }

    /** {@code int(text)} folosit ca indice: -1 daca textul nu e un numar sau iese din {@code int}. */
    static int index(String text) {
        BigInteger v = parseInt(text);
        if (v == null || v.bitLength() > 31) {
            return -1;
        }
        return v.intValue();
    }

    /** {@code float(text)}; {@code null} daca Python ar da {@code ValueError}. */
    static Double parseFloat(String text) {
        String s = text.strip();
        if (!FLOAT.matcher(s).matches()) {
            return null;
        }
        String t = ascii(s).toLowerCase();
        boolean neg = t.startsWith("-");
        String body = t.startsWith("-") || t.startsWith("+") ? t.substring(1) : t;
        if (body.equals("inf") || body.equals("infinity")) {
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (body.equals("nan")) {
            return Double.NaN;
        }
        return Double.parseDouble(t);
    }

    /** {@code float(v)}; {@code null} daca Python ar da {@code TypeError} / {@code ValueError}. */
    static Double toFloat(Object v) {
        if (isNum(v)) {
            return num(v);
        }
        if (v instanceof String s) {
            return parseFloat(s);
        }
        return null;
    }

    // ------------------------------------------------------------------ json.dumps
    /** {@code json.dumps(v, ensure_ascii=False)}; {@code compact} = {@code separators=(',', ':')}. */
    static String dumps(Object v, boolean compact) {
        StringBuilder sb = new StringBuilder(1 << 12);
        write(sb, v, compact ? "," : ", ", compact ? ":" : ": ");
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, String item, String key) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            number(sb, ((Number) v).doubleValue());
        } else if (v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(item);
                }
                first = false;
                Object k = e.getKey();
                if (k == null) {
                    sb.append("\"null\"");
                } else if (k instanceof Boolean b) {
                    sb.append(b ? "\"true\"" : "\"false\"");
                } else if (k instanceof Double || k instanceof Float) {
                    sb.append('"');
                    number(sb, ((Number) k).doubleValue());
                    sb.append('"');
                } else {
                    quote(sb, String.valueOf(k));
                }
                sb.append(key);
                write(sb, e.getValue(), item, key);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object x : list) {
                if (!first) {
                    sb.append(item);
                }
                first = false;
                write(sb, x, item, key);
            }
            sb.append(']');
        } else if (v instanceof int[] ints) {
            sb.append('[');
            for (int i = 0; i < ints.length; i++) {
                if (i > 0) {
                    sb.append(item);
                }
                sb.append(ints[i]);
            }
            sb.append(']');
        } else if (v instanceof double[] ds) {
            sb.append('[');
            for (int i = 0; i < ds.length; i++) {
                if (i > 0) {
                    sb.append(item);
                }
                number(sb, ds[i]);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            write(sb, List.of(arr), item, key);
        } else {
            quote(sb, String.valueOf(v));
        }
    }

    private static void number(StringBuilder sb, double d) {
        if (Double.isNaN(d)) {
            sb.append("NaN");
        } else if (Double.isInfinite(d)) {
            sb.append(d > 0 ? "Infinity" : "-Infinity");
        } else {
            sb.append(repr(d));
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
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
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
