package ng.unilag.mediqueue.repository;

import ng.unilag.mediqueue.db.Database;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC implementation of the four reports in Project.md 4.4.
 *
 * <p>Each is a single aggregate query. Pushing the counting into PostgreSQL rather than
 * loading rows and tallying them in Java matters more than it looks: a year of
 * appointments is hundreds of thousands of rows, and the database can answer from an
 * index without any of them crossing the network.
 */
public final class JdbcReportRepository implements ReportRepository {

    private final Database database;

    public JdbcReportRepository(Database database) {
        this.database = database;
    }

    /**
     * Daily attendance (4.4, bullet 1).
     *
     * <p>FILTER is PostgreSQL's conditional aggregate: it counts only rows matching each
     * condition, so all five figures come back from one pass over the day's rows instead
     * of five separate queries.
     */
    @Override
    public DailyAttendance dailyAttendance(LocalDate date) {
        return database.query(connection -> {
            String sql = """
                    SELECT COUNT(*)                                              AS booked,
                           COUNT(*) FILTER (WHERE status = 'ATTENDED')           AS attended,
                           COUNT(*) FILTER (WHERE status = 'SKIPPED')            AS skipped,
                           COUNT(*) FILTER (WHERE status = 'CANCELLED')          AS cancelled,
                           COUNT(*) FILTER (WHERE status IN ('BOOKED','WAITING')) AS still_waiting
                      FROM appointment
                     WHERE appointment_date = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setDate(1, Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new DailyAttendance(date, 0, 0, 0, 0, 0);
                    }
                    return new DailyAttendance(
                            date,
                            rs.getLong("booked"),
                            rs.getLong("attended"),
                            rs.getLong("skipped"),
                            rs.getLong("cancelled"),
                            rs.getLong("still_waiting"));
                }
            }
        });
    }

    /** Patient numbers by department (4.4, bullet 2). */
    @Override
    public List<Row> patientsByDepartment(LocalDate from, LocalDate to) {
        String sql = """
                SELECT d.name AS label, COUNT(a.id) AS value
                  FROM department d
                  LEFT JOIN appointment a
                         ON a.department_id = d.id
                        AND a.appointment_date BETWEEN ? AND ?
                        AND a.status <> 'CANCELLED'
                 GROUP BY d.name
                 ORDER BY value DESC, d.name
                """;
        // LEFT JOIN so a department with no patients still appears, showing a zero
        // rather than silently vanishing from the report.
        return rangeQuery(sql, from, to);
    }

    /**
     * Peak attendance periods (4.4, bullet 3): the hours of the day at which patients are
     * actually seen.
     *
     * <p>This is the figure a clinic manager acts on -- it says which hours need more
     * staff in the consulting rooms.
     *
     * <p>Both the filter and the grouping read from attendance, deliberately. An earlier
     * version filtered on appointment_date but grouped by the hour of booked_at, mixing
     * two unrelated clocks: when the visit was scheduled versus when the booking was
     * typed in. The answer looked plausible and meant nothing. Here a row is counted only
     * if the appointment fell in range AND the patient was attended, grouped by the hour
     * they were seen.
     *
     * <p>A consequence worth knowing: hours only appear once patients have been marked
     * attended, so this chart is legitimately empty on a new system. That is honest -- no
     * attendance has happened yet.
     */
    @Override
    public List<Row> peakPeriods(LocalDate from, LocalDate to) {
        String sql = """
                SELECT LPAD(EXTRACT(HOUR FROM attended_at)::text, 2, '0') || ':00' AS label,
                       COUNT(*) AS value
                  FROM appointment
                 WHERE appointment_date BETWEEN ? AND ?
                   AND status = 'ATTENDED'
                   AND attended_at IS NOT NULL
                 GROUP BY EXTRACT(HOUR FROM attended_at)
                 ORDER BY EXTRACT(HOUR FROM attended_at)
                """;
        return rangeQuery(sql, from, to);
    }

    /**
     * No-show statistics (4.4, bullet 4).
     *
     * <p>Counts SKIPPED only, not CANCELLED: a patient who cancels in advance frees the
     * slot and is not a no-show. Conflating the two would overstate the problem and point
     * at the wrong fix.
     */
    @Override
    public List<Row> noShowsByDepartment(LocalDate from, LocalDate to) {
        String sql = """
                SELECT d.name AS label, COUNT(a.id) AS value
                  FROM department d
                  LEFT JOIN appointment a
                         ON a.department_id = d.id
                        AND a.appointment_date BETWEEN ? AND ?
                        AND a.status = 'SKIPPED'
                 GROUP BY d.name
                 ORDER BY value DESC, d.name
                """;
        return rangeQuery(sql, from, to);
    }

    @Override
    public Totals totals(LocalDate today) {
        return database.query(connection -> {
            String sql = """
                    SELECT (SELECT COUNT(*) FROM app_user WHERE role = 'PATIENT')   AS patients,
                           (SELECT COUNT(*) FROM app_user WHERE role = 'STAFF')     AS staff,
                           (SELECT COUNT(*) FROM department)                        AS departments,
                           (SELECT COUNT(*) FROM appointment
                             WHERE appointment_date = ?)                            AS appointments_today,
                           (SELECT COUNT(*) FROM appointment
                             WHERE appointment_date = ?
                               AND status IN ('BOOKED','WAITING'))                  AS waiting_now
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setDate(1, Date.valueOf(today));
                ps.setDate(2, Date.valueOf(today));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new Totals(0, 0, 0, 0, 0);
                    }
                    return new Totals(
                            rs.getLong("patients"),
                            rs.getLong("staff"),
                            rs.getLong("departments"),
                            rs.getLong("appointments_today"),
                            rs.getLong("waiting_now"));
                }
            }
        });
    }

    /** Runs a label/value query bounded by a date range. */
    private List<Row> rangeQuery(String sql, LocalDate from, LocalDate to) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setDate(1, Date.valueOf(from));
                ps.setDate(2, Date.valueOf(to));
                try (ResultSet rs = ps.executeQuery()) {
                    return collect(rs);
                }
            }
        });
    }

    private List<Row> collect(ResultSet rs) throws SQLException {
        List<Row> rows = new ArrayList<>();
        while (rs.next()) {
            rows.add(new Row(rs.getString("label"), rs.getLong("value")));
        }
        return rows;
    }
}
