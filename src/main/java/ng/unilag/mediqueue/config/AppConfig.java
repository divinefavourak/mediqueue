package ng.unilag.mediqueue.config;

import ng.unilag.mediqueue.exception.MediQueueException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Application settings.
 *
 * <p>Values are resolved in this order, first match wins:
 *
 * <ol>
 *   <li><b>Environment variable</b> -- {@code db.user} becomes {@code MEDIQUEUE_DB_USER}.
 *       This is how hosting platforms and Docker supply configuration, and it is the only
 *       layer that should ever carry a real password.</li>
 *   <li><b>JVM system property</b> -- {@code -Ddb.url=...}, handy for a one-off run.</li>
 *   <li><b>config.properties</b> -- committed defaults for local development only.</li>
 * </ol>
 *
 * <p>Environment first is what lets the same build run on a laptop and in production
 * without editing a file. It also means production secrets never sit in the repository.
 *
 * <p>Spring Boot port: this class is replaced by application.properties plus
 * {@code @ConfigurationProperties}. Boot resolves environment variables with the same
 * SCREAMING_SNAKE_CASE convention, so the variable names below carry over unchanged.
 */
public final class AppConfig {

    private static final String ENV_PREFIX = "MEDIQUEUE_";

    private final Properties fileValues;

    private AppConfig(Properties fileValues) {
        this.fileValues = fileValues;
    }

    /** Loads config.properties from the classpath, falling back to src/main/resources. */
    public static AppConfig load() {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                props.load(in);
            } else {
                Path fallback = Path.of("src", "main", "resources", "config.properties");
                if (Files.exists(fallback)) {
                    try (InputStream fileIn = Files.newInputStream(fallback)) {
                        props.load(fileIn);
                    }
                }
            }
        } catch (IOException e) {
            throw new MediQueueException("Could not read config.properties", e);
        }
        return new AppConfig(props);
    }

    /** Resolves one key through the three layers described above. */
    private String get(String key, String fallback) {
        String fromEnv = System.getenv(envNameFor(key));
        if (isPresent(fromEnv)) {
            return fromEnv.trim();
        }
        String fromProperty = System.getProperty(key);
        if (isPresent(fromProperty)) {
            return fromProperty.trim();
        }
        String fromFile = fileValues.getProperty(key);
        return isPresent(fromFile) ? fromFile.trim() : fallback;
    }

    /** db.user -> MEDIQUEUE_DB_USER */
    private static String envNameFor(String key) {
        return ENV_PREFIX + key.replace('.', '_').toUpperCase(Locale.ROOT);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return isPresent(value) ? value.trim() : null;
    }

    // ------------------------------------------------------------------ server

    /**
     * The port to listen on.
     *
     * <p>Honours a bare {@code PORT} variable as well, because Render, Railway, Heroku and
     * Fly all assign the port that way and will not route traffic to anything else.
     */
    public int serverPort() {
        String platformPort = env("PORT");
        String value = platformPort != null ? platformPort : get("server.port", "8080");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new MediQueueException("Server port must be a number, got: " + value);
        }
    }

    // ---------------------------------------------------------------- database

    /**
     * JDBC URL for PostgreSQL.
     *
     * <p>If a platform-style {@code DATABASE_URL} is present it wins, converted to JDBC
     * form by {@link #parsePlatformDatabaseUrl}.
     */
    public String databaseUrl() {
        PlatformDatabase platform = platformDatabase();
        if (platform != null) {
            return platform.jdbcUrl();
        }
        return get("db.url", "jdbc:postgresql://localhost:5432/mediqueue");
    }

    public String databaseUser() {
        PlatformDatabase platform = platformDatabase();
        if (platform != null && isPresent(platform.user())) {
            return platform.user();
        }
        return get("db.user", "postgres");
    }

    public String databasePassword() {
        PlatformDatabase platform = platformDatabase();
        if (platform != null && isPresent(platform.password())) {
            return platform.password();
        }
        return get("db.password", "postgres");
    }

    /** Whether to create the schema at startup. */
    public boolean initialiseDatabase() {
        return Boolean.parseBoolean(get("db.initialise", "true"));
    }

    record PlatformDatabase(String jdbcUrl, String user, String password) {
    }

    private PlatformDatabase platformDatabase() {
        String raw = env("DATABASE_URL");
        return raw == null ? null : parsePlatformDatabaseUrl(raw);
    }

    /**
     * Converts a platform DATABASE_URL into JDBC form.
     *
     * <p>Managed Postgres providers hand out
     * {@code postgresql://user:password@host:5432/database}, which the JDBC driver does
     * not accept -- it needs {@code jdbc:postgresql://host:5432/database} with the
     * credentials passed separately. Missing that conversion is the classic reason a Java
     * app runs locally and dies on first boot in the cloud.
     *
     * <p>Any query string is preserved, because that is where {@code sslmode=require}
     * lives and most hosted databases refuse unencrypted connections.
     */
    static PlatformDatabase parsePlatformDatabaseUrl(String raw) {
        // Already JDBC form: pass it through untouched.
        if (raw.startsWith("jdbc:")) {
            return new PlatformDatabase(raw, null, null);
        }
        try {
            URI uri = new URI(raw);
            String host = uri.getHost();
            if (host == null) {
                throw new MediQueueException("DATABASE_URL has no host: " + raw);
            }
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();

            String database = uri.getPath() == null ? "" : uri.getPath();
            if (database.startsWith("/")) {
                database = database.substring(1);
            }

            StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                    .append(host).append(':').append(port).append('/').append(database);
            if (isPresent(uri.getQuery())) {
                jdbc.append('?').append(uri.getQuery());
            }

            String user = null;
            String password = null;
            String userInfo = uri.getUserInfo();
            if (isPresent(userInfo)) {
                int colon = userInfo.indexOf(':');
                user = colon < 0 ? userInfo : userInfo.substring(0, colon);
                password = colon < 0 ? null : userInfo.substring(colon + 1);
            }
            return new PlatformDatabase(jdbc.toString(), user, password);

        } catch (URISyntaxException e) {
            throw new MediQueueException("DATABASE_URL is not a valid URL: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- security

    public int pbkdf2Iterations() {
        return Integer.parseInt(get("security.pbkdf2.iterations", "120000"));
    }

    /**
     * Whether to mark the session cookie {@code Secure}, so browsers send it only over
     * HTTPS.
     *
     * <p>Must be configured rather than detected. A reverse proxy terminates TLS and
     * forwards plain HTTP, so the application always sees an unencrypted request and
     * would wrongly conclude the connection is insecure.
     *
     * <p>Off by default so http://localhost still works during development; every
     * production deployment must turn it on.
     */
    public boolean secureCookies() {
        return Boolean.parseBoolean(get("security.cookie.secure", "false"));
    }

    // -------------------------------------------------------------- first run

    /**
     * Whether to create the three published demo accounts on an empty database.
     *
     * <p>Off unless explicitly requested. These accounts share a password that is printed
     * in the README, so switching them on in production would publish an administrator
     * login for a system holding patient data.
     */
    public boolean seedDemoAccounts() {
        return Boolean.parseBoolean(get("demo.seed", "false"));
    }

    /** Email for the first administrator, created on an empty database. */
    public String adminEmail() {
        return get("admin.email", "");
    }

    /** Password for that administrator. Supply through the environment, never a file. */
    public String adminPassword() {
        return get("admin.password", "");
    }

    public boolean hasAdminBootstrap() {
        return isPresent(adminEmail()) && isPresent(adminPassword());
    }

    /** One line describing how this instance was configured, for the startup log. */
    public String describe() {
        return "port=" + serverPort()
                + " db=" + databaseUrl().replaceAll("://[^@/]+@", "://***@")
                + " secureCookies=" + secureCookies()
                + " demoAccounts=" + seedDemoAccounts();
    }
}
