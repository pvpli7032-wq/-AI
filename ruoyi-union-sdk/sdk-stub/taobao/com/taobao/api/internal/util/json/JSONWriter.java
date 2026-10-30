package com.taobao.api.internal.util.json;

import java.lang.reflect.Array;
import java.util.Map;
import java.util.StringJoiner;

/** Compile/test placeholder supporting the JSON-compatible values used by TbkController. */
public class JSONWriter {
    public JSONWriter(boolean ignoredFirstOption, boolean ignoredSecondOption) {
        // Preserve the existing constructor call without depending on the official SDK.
    }

    public String write(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return quote(value.toString());
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringJoiner entries = new StringJoiner(",", "{", "}");
            map.forEach((key, entry) -> entries.add(quote(String.valueOf(key)) + ":" + write(entry)));
            return entries.toString();
        }
        if (value instanceof Iterable<?> values) {
            StringJoiner entries = new StringJoiner(",", "[", "]");
            values.forEach(entry -> entries.add(write(entry)));
            return entries.toString();
        }
        if (value.getClass().isArray()) {
            StringJoiner entries = new StringJoiner(",", "[", "]");
            for (int index = 0; index < Array.getLength(value); index++) {
                entries.add(write(Array.get(value, index)));
            }
            return entries.toString();
        }
        throw new UnsupportedOperationException("占位 SDK：JSONWriter 不支持此对象类型");
    }

    private String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
