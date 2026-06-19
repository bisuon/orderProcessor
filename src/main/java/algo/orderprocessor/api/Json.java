package algo.orderprocessor.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal, dependency-free JSON helper supporting the flat objects exchanged by the
 * Order REST API (string / boolean / number values). This avoids pulling in a heavy
 * JSON library for what is intentionally a small, self-contained API surface.
 */
final class Json {

    private Json() {
    }

    /** Serialize a flat map into a JSON object string. */
    static String write(Map<String, Object> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(escape(value.toString())).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /**
     * Parse a flat JSON object. Only top-level string/boolean/number values are supported.
     * Values are returned as String, Boolean or Long.
     */
    static Map<String, Object> parse(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }
        String s = json.trim();
        if (s.isEmpty()) {
            return result;
        }
        if (!s.startsWith("{") || !s.endsWith("}")) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        s = s.substring(1, s.length() - 1).trim();
        if (s.isEmpty()) {
            return result;
        }

        int i = 0;
        while (i < s.length()) {
            i = skipWhitespace(s, i);
            if (s.charAt(i) != '"') {
                throw new IllegalArgumentException("Expected a string key at index " + i);
            }
            StringBuilder key = new StringBuilder();
            i = readString(s, i, key);
            i = skipWhitespace(s, i);
            if (s.charAt(i) != ':') {
                throw new IllegalArgumentException("Expected ':' at index " + i);
            }
            i = skipWhitespace(s, i + 1);

            char c = s.charAt(i);
            if (c == '"') {
                StringBuilder value = new StringBuilder();
                i = readString(s, i, value);
                result.put(key.toString(), value.toString());
            } else {
                int start = i;
                while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != '}') {
                    i++;
                }
                String token = s.substring(start, i).trim();
                result.put(key.toString(), parseLiteral(token));
            }

            i = skipWhitespace(s, i);
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
            }
        }
        return result;
    }

    private static Object parseLiteral(String token) {
        if (token.equals("true") || token.equals("false")) {
            return Boolean.valueOf(token);
        }
        if (token.equals("null")) {
            return null;
        }
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unsupported JSON value: " + token);
        }
    }

    private static int readString(String s, int quoteIndex, StringBuilder out) {
        int i = quoteIndex + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= s.length()) {
                    break;
                }
                char escaped = s.charAt(i);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    default -> out.append(escaped);
                }
            } else if (c == '"') {
                return i + 1;
            } else {
                out.append(c);
            }
            i++;
        }
        throw new IllegalArgumentException("Unterminated string literal");
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

