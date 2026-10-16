package com.expensetracker.experiments.extraction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JDK-only JSON writer for bounded synthetic artifacts. Money is passed in as strings. */
public final class JsonLines {
    private JsonLines() {}

    public static Map<String, Object> object(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("JSON object needs key/value pairs");
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    public static void write(Path path, List<? extends Map<String, ?>> rows) throws IOException {
        if (rows.size() > 10_000) throw new IllegalArgumentException("Artifact row limit exceeded");
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            for (Map<String, ?> row : rows) {
                String encoded = encode(row);
                if (encoded.length() > 2_000_000) throw new IllegalArgumentException("Artifact record limit exceeded");
                writer.write(encoded); writer.newLine();
            }
        }
    }

    public static String encode(Object value) {
        return encode(value, 0);
    }

    private static String encode(Object value, int depth) {
        if (depth > 20) throw new IllegalArgumentException("JSON nesting limit exceeded");
        if (value == null) return "null";
        if (value instanceof String || value instanceof Enum<?> || value instanceof TemporalAccessor || value instanceof Path) {
            return quote(value.toString());
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number number) {
            if (number instanceof Double d && !Double.isFinite(d) || number instanceof Float f && !Float.isFinite(f)) {
                throw new IllegalArgumentException("JSON number must be finite");
            }
            return number.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(entry -> {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON key must be text");
                return quote(key) + ":" + encode(entry.getValue(), depth + 1);
            }).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> values) {
            var encoded = new java.util.ArrayList<String>();
            for (Object item : values) encoded.add(encode(item, depth + 1));
            return String.join(",", encoded).transform(text -> "[" + text + "]");
        }
        throw new IllegalArgumentException("Unsupported artifact value type");
    }

    private static String quote(String value) {
        if (value.length() > 100_000) throw new IllegalArgumentException("JSON text limit exceeded");
        var result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 32 || Character.isSurrogate(character)) {
                        result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
