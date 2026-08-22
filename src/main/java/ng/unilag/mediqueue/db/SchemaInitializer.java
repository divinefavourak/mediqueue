package ng.unilag.mediqueue.db;

import ng.unilag.mediqueue.exception.MediQueueException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the schema at startup and seeds starter departments on an empty database.
 *
 * <p>schema.sql is idempotent (every statement is IF NOT EXISTS), so it runs on every
 * boot and quietly does nothing after the first. seed.sql is not idempotent, so it is
 * gated on the department table being empty -- otherwise a restart would resurrect
 * departments the administrator had deleted.
 *
 * <p>Spring Boot port: replaced by Flyway migrations, or by spring.sql.init.mode with
 * these same two files.
 */
public final class SchemaInitializer {

    private final Database database;

    public SchemaInitializer(Database database) {
        this.database = database;
    }

    public void run() {
        database.query(connection -> {
            execute(connection, readResource("/schema.sql"), "schema.sql");
            if (isDepartmentTableEmpty(connection)) {
                execute(connection, readResource("/seed.sql"), "seed.sql");
                System.out.println("[db] Seeded starter departments.");
            }
            return null;
        });
        System.out.println("[db] Schema ready.");
    }

    private boolean isDepartmentTableEmpty(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM department")) {
            return rs.next() && rs.getInt(1) == 0;
        }
    }

    /**
     * The PostgreSQL driver accepts several statements separated by semicolons in a
     * single execute, so the file is sent as-is rather than split by hand -- splitting
     * on ';' breaks the moment a literal or function body contains one.
     */
    private void execute(Connection connection, String sql, String label) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new MediQueueException("Failed applying " + label + ": " + e.getMessage(), e);
        }
    }

    /** Reads from the classpath, falling back to src/main/resources when run from an IDE. */
    private String readResource(String name) {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream(name)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Path fallback = Path.of("src", "main", "resources", name.substring(1));
            if (Files.exists(fallback)) {
                return Files.readString(fallback, StandardCharsets.UTF_8);
            }
            throw new MediQueueException("Missing SQL resource: " + name);
        } catch (IOException e) {
            throw new MediQueueException("Could not read " + name, e);
        }
    }
}
