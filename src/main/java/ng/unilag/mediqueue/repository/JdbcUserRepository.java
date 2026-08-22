package ng.unilag.mediqueue.repository;

import ng.unilag.mediqueue.db.Database;
import ng.unilag.mediqueue.model.Role;
import ng.unilag.mediqueue.model.Staff;
import ng.unilag.mediqueue.model.User;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link UserRepository}.
 *
 * <p>Every statement is a PreparedStatement with {@code ?} placeholders. That is not
 * style: string-concatenated SQL is how SQL injection happens, and a login form is the
 * first place anyone tries it. A parameter is sent to PostgreSQL separately from the
 * query text, so it can never be read as SQL no matter what it contains.
 *
 * <p>Spring Boot port: this whole class is deleted; Spring Data derives these queries
 * from method names on the interface.
 */
public final class JdbcUserRepository implements UserRepository {

    /** Selected in one place so every mapper sees the same column set. */
    private static final String COLUMNS = """
            u.id, u.role, u.full_name, u.email, u.phone,
            u.password_hash, u.password_salt, u.department_id, u.created_at,
            d.name AS department_name
            """;

    private static final String FROM_USER = " FROM app_user u LEFT JOIN department d ON d.id = u.department_id ";

    private final Database database;

    public JdbcUserRepository(Database database) {
        this.database = database;
    }

    @Override
    public User save(User user) {
        return database.query(connection -> {
            String sql = """
                    INSERT INTO app_user (role, full_name, email, phone,
                                          password_hash, password_salt, department_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, user.role().name());
                ps.setString(2, user.getFullName());
                ps.setString(3, user.getEmail());
                ps.setString(4, user.getPhone());
                ps.setString(5, user.getPasswordHash());
                ps.setString(6, user.getPasswordSalt());
                if (user instanceof Staff staff && staff.getDepartmentId() != null) {
                    ps.setLong(7, staff.getDepartmentId());
                } else {
                    ps.setNull(7, java.sql.Types.BIGINT);
                }
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        user.setId(keys.getLong("id"));
                    }
                }
                return user;
            }
        });
    }

    @Override
    public Optional<User> findById(long id) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + FROM_USER + " WHERE u.id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<User>empty();
                }
            }
        });
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return database.query(connection -> {
            // LOWER(email) matches the functional unique index, so this uses it.
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + FROM_USER + " WHERE LOWER(u.email) = LOWER(?)")) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<User>empty();
                }
            }
        });
    }

    @Override
    public boolean emailExists(String email) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM app_user WHERE LOWER(email) = LOWER(?)")) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public List<User> findAll() {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + FROM_USER + " ORDER BY u.role, u.full_name");
                 ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        });
    }

    @Override
    public List<User> findByRole(Role role) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + FROM_USER + " WHERE u.role = ? ORDER BY u.full_name")) {
                ps.setString(1, role.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return mapAll(rs);
                }
            }
        });
    }

    @Override
    public void deleteById(long id) {
        database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM app_user WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public long count() {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM app_user");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private List<User> mapAll(ResultSet rs) throws SQLException {
        List<User> users = new ArrayList<>();
        while (rs.next()) {
            users.add(map(rs));
        }
        return users;
    }

    /**
     * Turns a row into the right User subclass, driven by the role discriminator.
     * This method is the single point where a database row becomes a domain object.
     */
    private User map(ResultSet rs) throws SQLException {
        Role role = Role.fromDatabase(rs.getString("role"));
        User user = User.forRole(role);
        user.setId(rs.getLong("id"));
        user.setFullName(rs.getString("full_name"));
        user.setEmail(rs.getString("email"));
        user.setPhone(rs.getString("phone"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setPasswordSalt(rs.getString("password_salt"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            user.setCreatedAt(createdAt.toLocalDateTime());
        }
        if (user instanceof Staff staff) {
            long departmentId = rs.getLong("department_id");
            // getLong returns 0 for SQL NULL, so wasNull is the only reliable check.
            staff.setDepartmentId(rs.wasNull() ? null : departmentId);
            staff.setDepartmentName(rs.getString("department_name"));
        }
        return user;
    }
}
