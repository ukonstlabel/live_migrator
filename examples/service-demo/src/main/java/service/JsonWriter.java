package service;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Simple JSON serialization utility without external dependencies.
 *
 * <p>Supports serializing Maps, Collections, arrays, and primitive types to JSON strings.
 * Null values are serialized as the JSON literal {@code null}.
 */
public final class JsonWriter {

    private JsonWriter() {}

    /**
     * Convert an object to its JSON string representation.
     *
     * @param value the value to serialize (Map, Collection, array, String, Number, Boolean, or null)
     * @return JSON string representation
     */
    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(sb, map);
        } else if (value instanceof Collection<?> col) {
            writeCollection(sb, col);
        } else if (value.getClass().isArray()) {
            writeArray(sb, value);
        } else {
            // Fallback: treat as string
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
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
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<?, ?> entry = it.next();
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(':');
            writeValue(sb, entry.getValue());
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        sb.append('}');
    }

    private static void writeCollection(StringBuilder sb, Collection<?> col) {
        sb.append('[');
        Iterator<?> it = col.iterator();
        while (it.hasNext()) {
            writeValue(sb, it.next());
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        sb.append(']');
    }

    private static void writeArray(StringBuilder sb, Object array) {
        sb.append('[');
        int length = java.lang.reflect.Array.getLength(array);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, java.lang.reflect.Array.get(array, i));
        }
        sb.append(']');
    }
}
