package ng.unilag.mediqueue.web.support;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A minimal JSON writer, hand-built so MediQueue needs no third-party library.
 *
 * <p>Only serialisation is implemented, never parsing. That is not a shortcut but a
 * design choice: browsers can send request data as form encoding, which
 * {@code URLDecoder} already understands, so the parsing half of a JSON library is
 * simply not needed. Writing JSON is the easy direction; reading it safely is the hard
 * one.
 *
 * <p>Usage reads close to the output it produces:
 * <pre>
 *   Json.object()
 *       .put("id", 7)
 *       .put("name", "Ada")
 *       .toJson()                  // {"id":7,"name":"Ada"}
 * </pre>
 *
 * <p>Spring Boot port: deleted. Jackson serialises the same model objects automatically
 * once handlers become {@code @RestController}s.
 */
public final class Json {

    private Json() {
    }

    /** Starts a JSON object. Insertion order is preserved so output stays readable. */
    public static JsonObject object() {
        return new JsonObject();
    }

    /** Serialises a collection by applying {@code mapper} to each element. */
    public static <T> String array(Collection<T> items, Function<T, JsonObject> mapper) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (T item : items) {
            if (!first) {
                sb.append(',');
            }
            sb.append(mapper.apply(item).toJson());
            first = false;
        }
        return sb.append(']').toString();
    }

    /** A single-field object, for short replies such as {"message":"..."}. */
    public static String message(String key, Object value) {
        return object().put(key, value).toJson();
    }

    /** Mutable builder for one JSON object. */
    public static final class JsonObject {

        private final Map<String, String> fields = new LinkedHashMap<>();

        private JsonObject() {
        }

        /**
         * Adds a field. Strings are quoted and escaped; numbers and booleans are written
         * bare; null becomes JSON null. Anything else is rendered via toString and
         * quoted, which is what makes enums, dates and times serialise correctly.
         */
        public JsonObject put(String key, Object value) {
            fields.put(key, encode(value));
            return this;
        }

        /** Adds a value that is already valid JSON, such as a nested object or array. */
        public JsonObject putRaw(String key, String rawJson) {
            fields.put(key, rawJson == null ? "null" : rawJson);
            return this;
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(escape(field.getKey())).append("\":").append(field.getValue());
                first = false;
            }
            return sb.append('}').toString();
        }

        @Override
        public String toString() {
            return toJson();
        }
    }

    private static String encode(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return '"' + escape(value.toString()) + '"';
    }

    /**
     * Escapes a string for inclusion in JSON.
     *
     * <p>This is the security-critical part of the class. Without it, a patient
     * registering as {@code Ada", "role":"ADMIN} would inject fields into every JSON
     * document their name appears in. Control characters below 0x20 are escaped as \\u
     * sequences because raw ones make the document invalid.
     */
    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
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
        return sb.toString();
    }
}
