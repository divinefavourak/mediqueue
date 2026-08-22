package ng.unilag.mediqueue.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the HTML, CSS and JavaScript that make up the user interface.
 *
 * <p>Files are read from the classpath when the app is packaged, and from
 * src/main/resources/static when it is not, so edits to a page show up on refresh
 * without a rebuild.
 *
 * <p>Spring Boot port: delete this class. Boot serves src/main/resources/static
 * automatically -- which is exactly why the pages live at that path already.
 */
public final class StaticFileHandler implements HttpHandler {

    private static final String CLASSPATH_ROOT = "/static";
    private static final Path SOURCE_ROOT = Path.of("src", "main", "resources", "static");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }
            // A bare /patient/ should serve that folder's index page.
            if (path.endsWith("/")) {
                path = path + "index.html";
            }

            if (!isSafe(path)) {
                send(exchange, 400, "text/plain", "Bad request".getBytes(StandardCharsets.UTF_8));
                return;
            }

            byte[] body = read(path);
            if (body == null) {
                send(exchange, 404, "text/html", notFoundPage().getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, contentType(path), body);
        } finally {
            exchange.close();
        }
    }

    /**
     * Blocks path traversal.
     *
     * <p>Without this check, a request for {@code /../../../../config.properties} would
     * escape the static folder and hand out the database password. Directory traversal is
     * the classic file-server vulnerability, and the only safe answer is to reject the
     * pattern outright rather than try to normalise it.
     */
    private boolean isSafe(String path) {
        return path.startsWith("/")
                && !path.contains("..")
                && !path.contains("\\")
                && !path.contains("\0");
    }

    private byte[] read(String path) throws IOException {
        // Classpath first, so a packaged jar is self-contained.
        try (InputStream in = StaticFileHandler.class.getResourceAsStream(CLASSPATH_ROOT + path)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        Path onDisk = SOURCE_ROOT.resolve(path.substring(1)).normalize();
        // Re-check after normalising: resolve() could still land outside the root.
        if (!onDisk.startsWith(SOURCE_ROOT) || !Files.isRegularFile(onDisk)) {
            return null;
        }
        return Files.readAllBytes(onDisk);
    }

    private String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
        return switch (extension) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "application/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // Pages change during development; caching them makes edits appear not to work.
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private String notFoundPage() {
        return """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Page not found - MediQueue</title>
                <link rel="icon" href="/img/mark.svg" type="image/svg+xml">
                <link rel="stylesheet" href="/css/styles.css"></head>
                <body class="centred">
                  <main>
                    <a class="wordmark" href="/" style="margin-bottom:1.25rem">
                      <img src="/img/mark.svg" alt="" width="22" height="22">MediQueue</a>
                    <section class="sheet">
                      <h1 style="font-size:1.5rem">No such page</h1>
                      <p class="muted">That address does not lead anywhere. Check the link, or start again.</p>
                      <a class="btn" href="/">Back to MediQueue</a>
                    </section>
                  </main>
                </body>
                </html>
                """;
    }
}
