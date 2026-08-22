package ng.unilag.mediqueue.web.support;

import com.sun.net.httpserver.HttpExchange;
import ng.unilag.mediqueue.exception.ValidationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Helpers for reading from and replying to an {@link HttpExchange}.
 *
 * <p>The JDK's HttpServer is deliberately bare: it hands over a raw stream and a header
 * map and leaves parsing to the caller. Everything the handlers need -- query strings,
 * form bodies, path segments, cookies, JSON replies -- is gathered here so no handler
 * has to repeat it.
 *
 * <p>Spring Boot port: deleted. {@code @RequestParam}, {@code @PathVariable} and
 * {@code @RequestBody} do all of this declaratively.
 */
public final class HttpExchanges {

    /** Caps request bodies so a malicious client cannot exhaust heap with one POST. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private HttpExchanges() {
    }

    // ---------------------------------------------------------------- reading

    /** Parses ?a=1&b=2 from the request URI. Returns an empty map when there is no query. */
    public static Map<String, String> queryParams(HttpExchange exchange) {
        return parseUrlEncoded(exchange.getRequestURI().getRawQuery());
    }

    /**
     * Reads and parses an application/x-www-form-urlencoded request body.
     *
     * <p>This is why MediQueue needs no JSON parser: browsers can post form encoding
     * natively, and URLDecoder handles it in one line.
     */
    public static Map<String, String> formBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES);
            return parseUrlEncoded(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> parseUrlEncoded(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Returns the path segment at {@code index}, counting from the start of the path.
     * For /api/queue/position/12 segment 3 is "12".
     */
    public static Optional<String> pathSegment(HttpExchange exchange, int index) {
        String[] segments = exchange.getRequestURI().getPath().split("/");
        // segments[0] is always empty because the path begins with '/'.
        int actual = index + 1;
        if (actual >= segments.length || segments[actual].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(segments[actual]);
    }

    /** Reads a cookie by name from the request headers. */
    public static Optional<String> cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return Optional.empty();
        }
        for (String header : headers) {
            for (String candidate : header.split(";")) {
                String trimmed = candidate.trim();
                if (trimmed.startsWith(name + "=")) {
                    return Optional.of(trimmed.substring(name.length() + 1));
                }
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------ validation

    /** Returns a required field, or fails with a message the user can act on. */
    public static String required(Map<String, String> values, String field) {
        String value = values.get(field);
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " is required.");
        }
        return value.trim();
    }

    public static long requiredLong(Map<String, String> values, String field) {
        String raw = required(values, field);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ValidationException(field + " must be a number.");
        }
    }

    public static LocalDate requiredDate(Map<String, String> values, String field) {
        String raw = required(values, field);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ValidationException(field + " must be a date in YYYY-MM-DD format.");
        }
    }

    /** Parses an optional date parameter, falling back to {@code fallback} when absent. */
    public static LocalDate optionalDate(Map<String, String> values, String field, LocalDate fallback) {
        String raw = values.get(field);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException(field + " must be a date in YYYY-MM-DD format.");
        }
    }

    /** Parses a long from a path segment, failing with a clear message when malformed. */
    public static long pathId(HttpExchange exchange, int index, String label) {
        String raw = pathSegment(exchange, index)
                .orElseThrow(() -> new ValidationException(label + " is missing from the URL."));
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ValidationException(label + " must be a number.");
        }
    }

    // ---------------------------------------------------------------- replying

    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // Queue positions change constantly; a cached one is a wrong one.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    public static void sendOk(HttpExchange exchange, String json) throws IOException {
        sendJson(exchange, 200, json);
    }

    public static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Json.object().put("error", message).toJson());
    }

    /**
     * Sets the session cookie.
     *
     * <p>Three attributes carry the security, and each blocks a different attack:
     * {@code HttpOnly} keeps the token out of reach of JavaScript, so an XSS bug cannot
     * steal a session; {@code SameSite=Strict} stops the browser attaching it to
     * cross-site requests, which is CSRF; {@code Secure} tells the browser never to send
     * it over plain HTTP.
     *
     * <p>{@code secure} has to be supplied rather than sniffed from the request. Behind a
     * reverse proxy the application only ever sees plain HTTP, so detecting it would
     * silently disable the flag on exactly the deployments that need it.
     */
    public static void setSessionCookie(HttpExchange exchange, String cookieName,
                                        String token, int maxAgeSeconds, boolean secure) {
        exchange.getResponseHeaders().add("Set-Cookie",
                cookieName + "=" + token + attributes(secure) + "; Max-Age=" + maxAgeSeconds);
    }

    /**
     * Expires the session cookie.
     *
     * <p>The attributes must match those it was set with, or the browser treats this as a
     * different cookie and quietly leaves the original in place -- a sign-out that does
     * not sign anyone out.
     */
    public static void clearSessionCookie(HttpExchange exchange, String cookieName, boolean secure) {
        exchange.getResponseHeaders().add("Set-Cookie",
                cookieName + "=" + attributes(secure) + "; Max-Age=0");
    }

    private static String attributes(boolean secure) {
        return "; Path=/; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
    }
}
