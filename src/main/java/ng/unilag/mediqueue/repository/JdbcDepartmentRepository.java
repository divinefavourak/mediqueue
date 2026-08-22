package ng.unilag.mediqueue.repository;

import ng.unilag.mediqueue.db.Database;
import ng.unilag.mediqueue.model.Department;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC implementation of {@link DepartmentRepository}. */
public final class JdbcDepartmentRepository implements DepartmentRepository {

    private static final String COLUMNS = "id, name, opens_at, closes_at, daily_capacity, active";

    private final Database database;

    public JdbcDepartmentRepository(Database database) {
        this.database = database;
    }

    @Override
    public Department save(Department department) {
        return database.query(connection -> {
            String sql = """
                    INSERT INTO department (name, opens_at, closes_at, daily_capacity, active)
                    VALUES (?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, department.getName());
                ps.setObject(2, department.getOpensAt());
                ps.setObject(3, department.getClosesAt());
                ps.setInt(4, department.getDailyCapacity());
                ps.setBoolean(5, department.isActive());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        department.setId(keys.getLong("id"));
                    }
                }
                return department;
            }
        });
    }

    @Override
    public Department update(Department department) {
        return database.query(connection -> {
            String sql = """
                    UPDATE department
                       SET name = ?, opens_at = ?, closes_at = ?, daily_capacity = ?, active = ?
                     WHERE id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, department.getName());
                ps.setObject(2, department.getOpensAt());
                ps.setObject(3, department.getClosesAt());
                ps.setInt(4, department.getDailyCapacity());
                ps.setBoolean(5, department.isActive());
                ps.setLong(6, department.getId());
                ps.executeUpdate();
                return department;
            }
        });
    }

    @Override
    public Optional<Department> findById(long id) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM department WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Department>empty();
                }
            }
        });
    }

    @Override
    public Optional<Department> findByName(String name) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM department WHERE LOWER(name) = LOWER(?)")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Department>empty();
                }
            }
        });
    }

    @Override
    public List<Department> findAll() {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM department ORDER BY name");
                 ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        });
    }

    @Override
    public List<Department> findActive() {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM department WHERE active = TRUE ORDER BY name");
                 ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        });
    }

    @Override
    public void deleteById(long id) {
        database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM department WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public long count() {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM department");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private List<Department> mapAll(ResultSet rs) throws SQLException {
        List<Department> departments = new ArrayList<>();
        while (rs.next()) {
            departments.add(map(rs));
        }
        return departments;
    }

    private Department map(ResultSet rs) throws SQLException {
        Department department = new Department();
        department.setId(rs.getLong("id"));
        department.setName(rs.getString("name"));
        // getObject with a target type keeps java.time out of java.sql conversions.
        department.setOpensAt(rs.getObject("opens_at", java.time.LocalTime.class));
        department.setClosesAt(rs.getObject("closes_at", java.time.LocalTime.class));
        department.setDailyCapacity(rs.getInt("daily_capacity"));
        department.setActive(rs.getBoolean("active"));
        return department;
    }
}
