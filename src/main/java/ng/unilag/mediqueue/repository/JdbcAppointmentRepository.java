package ng.unilag.mediqueue.repository;

import ng.unilag.mediqueue.db.Database;
import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.model.Appointment;
import ng.unilag.mediqueue.model.AppointmentStatus;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link AppointmentRepository}, including the queue arithmetic
 * described in Project.md 4.3.
 */
public final class JdbcAppointmentRepository implements AppointmentRepository {

    private static final String COLUMNS = """
            a.id, a.patient_id, a.department_id, a.appointment_date, a.queue_number,
            a.status, a.booked_at, a.attended_at,
            p.full_name AS patient_name, p.phone AS patient_phone,
            d.name AS department_name
            """;

    private static final String FROM_APPOINTMENT = """
             FROM appointment a
             JOIN app_user p ON p.id = a.patient_id
             JOIN department d ON d.id = a.department_id
            """;

    /** The two statuses that hold a place in line, from AppointmentStatus. */
    private static final String WAITING_STATES = "('BOOKED', 'WAITING')";

    private final Database database;

    public JdbcAppointmentRepository(Database database) {
        this.database = database;
    }

    /**
     * Reserves the next ticket number for a department and date.
     *
     * <p>This is the one genuine race condition in MediQueue. Two patients booking the
     * same clinic day at the same moment can both read {@code MAX(queue_number) = 7} and
     * both try to take ticket 8.
     *
     * <p>The fix is a two-step lock-then-read, and the order matters.
     *
     * <ol>
     *   <li><b>Lock the department row</b> with {@code SELECT ... FOR UPDATE}. Only one
     *       transaction at a time may hold it, so bookings for a department are
     *       serialised -- the database equivalent of one person at a time taking a ticket
     *       from that department's dispenser. Other departments book in parallel, since
     *       each has its own row.</li>
     *   <li><b>Then read the maximum.</b> The second transaction blocks at step 1 until
     *       the first commits, so by the time it reads, the earlier ticket is committed
     *       and visible.</li>
     * </ol>
     *
     * <p>The lock cannot be taken on the aggregate query itself: PostgreSQL rejects
     * {@code FOR UPDATE} alongside an aggregate function, because {@code MAX()} collapses
     * many rows into one value and there is no row left to lock. Locking a real row that
     * always exists -- the department -- is what gives the lock something to hold.
     *
     * <p>The UNIQUE constraint in schema.sql remains the backstop: if this lock were ever
     * bypassed, the database still refuses to hand two patients the same ticket.
     */
    @Override
    public int reserveNextQueueNumber(Connection connection, long departmentId, LocalDate date) {
        try {
            // Step 1: serialise bookings for this department.
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT id FROM department WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, departmentId);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) {
                        throw new NotFoundException("That department does not exist.");
                    }
                }
            }

            // Step 2: now safe to read the day's highest ticket.
            String sql = """
                    SELECT COALESCE(MAX(queue_number), 0) AS highest
                      FROM appointment
                     WHERE department_id = ? AND appointment_date = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, departmentId);
                ps.setDate(2, Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    return (rs.next() ? rs.getInt("highest") : 0) + 1;
                }
            }
        } catch (SQLException e) {
            throw new ng.unilag.mediqueue.exception.MediQueueException(
                    "Could not reserve a queue number: " + e.getMessage(), e);
        }
    }

    @Override
    public Appointment save(Connection connection, Appointment appointment) {
        String sql = """
                INSERT INTO appointment (patient_id, department_id, appointment_date,
                                         queue_number, status)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, appointment.getPatientId());
            ps.setLong(2, appointment.getDepartmentId());
            ps.setDate(3, Date.valueOf(appointment.getAppointmentDate()));
            ps.setInt(4, appointment.getQueueNumber());
            ps.setString(5, appointment.getStatus().name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    appointment.setId(keys.getLong("id"));
                }
            }
            return appointment;
        } catch (SQLException e) {
            throw new ng.unilag.mediqueue.exception.MediQueueException(
                    "Could not save appointment: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Appointment> findById(long id) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + FROM_APPOINTMENT + " WHERE a.id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Appointment>empty();
                }
            }
        });
    }

    @Override
    public List<Appointment> findByPatient(long patientId) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + FROM_APPOINTMENT
                            + " WHERE a.patient_id = ? ORDER BY a.appointment_date DESC, a.queue_number")) {
                ps.setLong(1, patientId);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapAll(rs);
                }
            }
        });
    }

    @Override
    public List<Appointment> findDailyQueue(long departmentId, LocalDate date) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + FROM_APPOINTMENT
                            + " WHERE a.department_id = ? AND a.appointment_date = ?"
                            + " ORDER BY a.queue_number")) {
                ps.setLong(1, departmentId);
                ps.setDate(2, Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    return mapAll(rs);
                }
            }
        });
    }

    /**
     * The heart of Project.md 4.3: how many still-waiting patients hold a lower ticket
     * number. Position is this count plus one -- never stored, always current, and served
     * entirely from the idx_appt_queue index.
     */
    @Override
    public int countAheadOf(long departmentId, LocalDate date, int queueNumber) {
        return database.query(connection -> {
            String sql = """
                    SELECT COUNT(*) FROM appointment
                     WHERE department_id = ? AND appointment_date = ?
                       AND queue_number < ?
                       AND status IN
                    """ + WAITING_STATES;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, departmentId);
                ps.setDate(2, Date.valueOf(date));
                ps.setInt(3, queueNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public int countWaiting(long departmentId, LocalDate date) {
        return countByStatusList(departmentId, date, WAITING_STATES);
    }

    @Override
    public int currentlyServing(long departmentId, LocalDate date) {
        return database.query(connection -> {
            String sql = """
                    SELECT COALESCE(MIN(queue_number), 0) FROM appointment
                     WHERE department_id = ? AND appointment_date = ?
                       AND status = 'IN_PROGRESS'
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, departmentId);
                ps.setDate(2, Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /**
     * Median minutes between consecutive patients being seen today.
     *
     * <p>LAG pairs each attended appointment with the one before it, so the differences
     * are the real service intervals the department is achieving right now.
     *
     * <p>Two guards matter. The median rather than the average, because one long
     * consultation or a lunch break would drag an average far off. And gaps beyond four
     * hours are dropped: those are breaks and clerical catch-ups, not service time, and a
     * clinic that marks ten patients attended at closing time would otherwise poison the
     * figure for everyone still waiting.
     */
    @Override
    public Optional<ServicePace> servicePace(long departmentId, LocalDate date) {
        return database.query(connection -> {
            String sql = """
                    WITH seen AS (
                        SELECT attended_at,
                               LAG(attended_at) OVER (ORDER BY attended_at) AS previous
                          FROM appointment
                         WHERE department_id = ? AND appointment_date = ?
                           AND status = 'ATTENDED' AND attended_at IS NOT NULL
                    ),
                    gaps AS (
                        SELECT EXTRACT(EPOCH FROM (attended_at - previous)) / 60.0 AS minutes
                          FROM seen
                         WHERE previous IS NOT NULL
                    )
                    SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY minutes) AS median,
                           COUNT(*) AS samples
                      FROM gaps
                     WHERE minutes > 0 AND minutes < 240
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, departmentId);
                ps.setDate(2, Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<ServicePace>empty();
                    }
                    int samples = rs.getInt("samples");
                    double median = rs.getDouble("median");
                    // percentile_cont returns SQL NULL over an empty set.
                    if (samples == 0 || rs.wasNull()) {
                        return Optional.<ServicePace>empty();
                    }
                    return Optional.of(new ServicePace(median, samples));
                }
            }
        });
    }

    @Override
    public int countBookedOn(long departmentId, LocalDate date) {
        // Cancelled slots free up capacity again, so they are excluded.
        return countByStatusList(departmentId, date,
                "('BOOKED', 'WAITING', 'IN_PROGRESS', 'ATTENDED', 'SKIPPED')");
    }

    private int countByStatusList(long departmentId, LocalDate date, String statusList) {
        return database.query(connection -> {
            String sql = "SELECT COUNT(*) FROM appointment"
                    + " WHERE department_id = ? AND appointment_date = ? AND status IN " + statusList;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, departmentId);
                ps.setDate(2, Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public boolean hasActiveBooking(long patientId, long departmentId, LocalDate date) {
        return database.query(connection -> {
            String sql = "SELECT 1 FROM appointment"
                    + " WHERE patient_id = ? AND department_id = ? AND appointment_date = ?"
                    + " AND status IN " + WAITING_STATES;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, patientId);
                ps.setLong(2, departmentId);
                ps.setDate(3, Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public void updateStatus(long appointmentId, AppointmentStatus status) {
        database.query(connection -> {
            // attended_at is stamped only on the transition to ATTENDED, and only once.
            String sql = """
                    UPDATE appointment
                       SET status = ?,
                           attended_at = CASE WHEN ? = 'ATTENDED' THEN now() ELSE attended_at END
                     WHERE id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, status.name());
                ps.setString(2, status.name());
                ps.setLong(3, appointmentId);
                if (ps.executeUpdate() == 0) {
                    throw new NotFoundException("Appointment " + appointmentId + " does not exist.");
                }
                return null;
            }
        });
    }

    /**
     * Moves an appointment to another day. The old ticket is meaningless on the new date,
     * so a fresh one is reserved under the same lock booking uses.
     */
    @Override
    public Appointment reschedule(Connection connection, long appointmentId, LocalDate newDate) {
        try {
            long departmentId;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT department_id FROM appointment WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, appointmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new NotFoundException("Appointment " + appointmentId + " does not exist.");
                    }
                    departmentId = rs.getLong("department_id");
                }
            }

            int newQueueNumber = reserveNextQueueNumber(connection, departmentId, newDate);

            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE appointment
                       SET appointment_date = ?, queue_number = ?, status = 'BOOKED'
                     WHERE id = ?
                    """)) {
                ps.setDate(1, Date.valueOf(newDate));
                ps.setInt(2, newQueueNumber);
                ps.setLong(3, appointmentId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + FROM_APPOINTMENT + " WHERE a.id = ?")) {
                ps.setLong(1, appointmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new NotFoundException("Appointment " + appointmentId + " vanished mid-reschedule.");
                    }
                    return map(rs);
                }
            }
        } catch (SQLException e) {
            throw new ng.unilag.mediqueue.exception.MediQueueException(
                    "Could not reschedule appointment: " + e.getMessage(), e);
        }
    }

    @Override
    public long count() {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM appointment");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private List<Appointment> mapAll(ResultSet rs) throws SQLException {
        List<Appointment> appointments = new ArrayList<>();
        while (rs.next()) {
            appointments.add(map(rs));
        }
        return appointments;
    }

    private Appointment map(ResultSet rs) throws SQLException {
        Appointment appointment = new Appointment();
        appointment.setId(rs.getLong("id"));
        appointment.setPatientId(rs.getLong("patient_id"));
        appointment.setDepartmentId(rs.getLong("department_id"));
        appointment.setAppointmentDate(rs.getDate("appointment_date").toLocalDate());
        appointment.setQueueNumber(rs.getInt("queue_number"));
        appointment.setStatus(AppointmentStatus.fromDatabase(rs.getString("status")));
        Timestamp bookedAt = rs.getTimestamp("booked_at");
        if (bookedAt != null) {
            appointment.setBookedAt(bookedAt.toLocalDateTime());
        }
        Timestamp attendedAt = rs.getTimestamp("attended_at");
        if (attendedAt != null) {
            appointment.setAttendedAt(attendedAt.toLocalDateTime());
        }
        appointment.setPatientName(rs.getString("patient_name"));
        appointment.setPatientPhone(rs.getString("patient_phone"));
        appointment.setDepartmentName(rs.getString("department_name"));
        return appointment;
    }
}
