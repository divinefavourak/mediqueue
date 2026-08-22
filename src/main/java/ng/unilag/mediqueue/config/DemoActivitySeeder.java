package ng.unilag.mediqueue.config;

import ng.unilag.mediqueue.db.Database;
import ng.unilag.mediqueue.model.Department;
import ng.unilag.mediqueue.model.User;
import ng.unilag.mediqueue.service.AuthService;
import ng.unilag.mediqueue.service.DepartmentService;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fills a demonstration database with a clinic's worth of believable activity.
 *
 * <p>An empty system demonstrates nothing: every queue reads zero, every report is blank,
 * and the wait estimate stays hidden because there is no pace to measure. This creates a
 * fortnight of history, a live queue for today, and a few days of upcoming bookings.
 *
 * <p>Appointments are written straight to the database rather than through
 * AppointmentService, for two reasons. Historical rows need explicit booked_at and
 * attended_at timestamps, and the service quite rightly refuses to book in the past or at
 * weekends. Seeding is the one place that legitimately bypasses those rules.
 *
 * <p>The randomness is seeded with a fixed value, so the same demo comes back after every
 * reset and a screenshot taken today still matches the system tomorrow.
 */
public final class DemoActivitySeeder {

    /** Fixed seed: a reproducible demo is worth more than a novel one. */
    private static final Random RANDOM = new Random(20260822L);

    private static final String[] PATIENT_NAMES = {
            "Ada Nwosu", "Bola Adeyemi", "Ngozi Eze", "Emeka Nwachukwu", "Fatima Bello",
            "Tunde Bakare", "Chiamaka Obi", "Yusuf Danjuma", "Blessing Okon", "Ibrahim Sani",
            "Folake Adeniyi", "Kelechi Uche", "Aisha Mohammed", "Segun Balogun", "Nneka Anyanwu"
    };

    private final Database database;
    private final AuthService authService;
    private final DepartmentService departmentService;

    public DemoActivitySeeder(Database database, AuthService authService,
                              DepartmentService departmentService) {
        this.database = database;
        this.authService = authService;
        this.departmentService = departmentService;
    }

    /** Runs only on a database with no appointments, so it never doubles up. */
    public void seedIfEmpty() {
        if (countAppointments() > 0) {
            return;
        }
        List<Department> departments = departmentService.listActive();
        if (departments.isEmpty()) {
            return;
        }
        List<Long> patients = ensurePatients();

        int history = seedHistory(departments, patients);
        int today = seedToday(departments, patients);
        int upcoming = seedUpcoming(departments, patients);
        writeAll();

        System.out.printf("[seed] Demo activity: %d past, %d today, %d upcoming appointments.%n",
                history, today, upcoming);
    }

    // ------------------------------------------------------------- patients

    /** Creates the demo patients, reusing any that already exist. */
    private List<Long> ensurePatients() {
        List<Long> ids = new ArrayList<>();

        // The published demo patient comes first, so signing in as them shows a live queue.
        authService.listAll().stream()
                .filter(u -> u.getEmail().equals("patient@mediqueue.ng"))
                .map(User::getId)
                .forEach(ids::add);

        for (int i = 0; i < PATIENT_NAMES.length; i++) {
            String name = PATIENT_NAMES[i];
            String email = name.toLowerCase().replace(' ', '.') + "@example.ng";
            try {
                ids.add(authService.registerPatient(
                        name, email, "080" + (30000000 + i * 111), DemoDataSeeder.DEMO_PASSWORD).getId());
            } catch (RuntimeException alreadyExists) {
                authService.listAll().stream()
                        .filter(u -> u.getEmail().equalsIgnoreCase(email))
                        .map(User::getId)
                        .forEach(ids::add);
            }
        }
        return ids;
    }

    // -------------------------------------------------------------- history

    /**
     * A fortnight of finished clinic days, so the reports have something to describe.
     *
     * <p>Roughly one appointment in eight is a no-show and one in twelve was cancelled,
     * which is the shape a real outpatient clinic tends to have and makes the no-show
     * report worth looking at.
     */
    private int seedHistory(List<Department> departments, List<Long> patients) {
        int written = 0;
        for (int daysAgo = 14; daysAgo >= 1; daysAgo--) {
            LocalDate date = LocalDate.now().minusDays(daysAgo);
            if (isWeekend(date)) {
                continue;
            }
            for (Department department : departments) {
                int count = 4 + RANDOM.nextInt(Math.max(1, department.getDailyCapacity() / 5));
                LocalDateTime clock = date.atTime(department.getOpensAt());

                for (int ticket = 1; ticket <= count; ticket++) {
                    clock = clock.plusMinutes(4 + RANDOM.nextInt(9));
                    int roll = RANDOM.nextInt(100);

                    String status = roll < 12 ? "SKIPPED" : roll < 20 ? "CANCELLED" : "ATTENDED";
                    LocalDateTime attendedAt = status.equals("ATTENDED") ? clock : null;

                    insert(patients.get(RANDOM.nextInt(patients.size())), department.getId(),
                            date, ticket, status,
                            date.minusDays(2 + RANDOM.nextInt(5)).atTime(9, 30), attendedAt);
                    written++;
                }
            }
        }
        return written;
    }

    // ---------------------------------------------------------------- today

    /**
     * Today's live queues.
     *
     * <p>The important part is the spacing of attended_at. The wait estimate measures the
     * gaps between patients being seen, so a day where everyone was marked attended at the
     * same instant would produce an estimate of zero. These are spaced by a per-department
     * pace, which is what makes "About 20 minutes" appear on the patient screen.
     */
    private int seedToday(List<Department> departments, List<Long> patients) {
        LocalDate today = LocalDate.now();
        int written = 0;

        for (int i = 0; i < departments.size(); i++) {
            Department department = departments.get(i);

            // Different departments move at different speeds, which is the whole point of
            // measuring pace per department rather than clinic-wide.
            int paceMinutes = 5 + (i * 3) % 11;
            int seen = 4 + RANDOM.nextInt(5);
            int waiting = 3 + RANDOM.nextInt(6);

            LocalDateTime opened = LocalDateTime.of(today, latest(department.getOpensAt(),
                    LocalTime.now().minusMinutes((long) paceMinutes * (seen + 1))));

            int ticket = 1;
            LocalDateTime clock = opened;

            for (int s = 0; s < seen; s++, ticket++) {
                clock = clock.plusMinutes(paceMinutes + RANDOM.nextInt(3) - 1);
                insert(patients.get(RANDOM.nextInt(patients.size())), department.getId(),
                        today, ticket, "ATTENDED", today.minusDays(3).atTime(10, 15), clock);
                written++;
            }

            // One patient in the room, so the "Now serving" card has something to show.
            insert(patients.get(RANDOM.nextInt(patients.size())), department.getId(),
                    today, ticket++, "IN_PROGRESS", today.minusDays(3).atTime(10, 20), null);
            written++;

            // The demo patient always holds a live ticket in the first department, so
            // signing in as them lands on a queue that is actually moving.
            if (i == 0) {
                insert(patients.get(0), department.getId(), today, ticket++, "BOOKED",
                        today.minusDays(2).atTime(8, 5), null);
                written++;
            }

            for (int w = 0; w < waiting; w++, ticket++) {
                insert(patients.get(RANDOM.nextInt(patients.size())), department.getId(),
                        today, ticket, "BOOKED", today.minusDays(1 + RANDOM.nextInt(4)).atTime(11, 0), null);
                written++;
            }

            // One absentee, so today's attendance figures are not a clean sweep.
            insert(patients.get(RANDOM.nextInt(patients.size())), department.getId(),
                    today, ticket, "SKIPPED", today.minusDays(2).atTime(9, 0), null);
            written++;
        }
        return written;
    }

    private static LocalTime latest(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    // ------------------------------------------------------------- upcoming

    /** A few days of future bookings, so the booking screen and reports look inhabited. */
    private int seedUpcoming(List<Department> departments, List<Long> patients) {
        int written = 0;
        LocalDate date = LocalDate.now();
        int days = 0;

        while (days < 5) {
            date = date.plusDays(1);
            if (isWeekend(date)) {
                continue;
            }
            days++;
            for (Department department : departments) {
                int count = 2 + RANDOM.nextInt(6);
                for (int ticket = 1; ticket <= count; ticket++) {
                    insert(patients.get(RANDOM.nextInt(patients.size())), department.getId(),
                            date, ticket, "BOOKED", LocalDateTime.now().minusHours(ticket), null);
                    written++;
                }
            }
        }
        return written;
    }

    // ------------------------------------------------------------------ SQL

    /** One appointment to be written. */
    private record Row(long patientId, long departmentId, LocalDate date, int queueNumber,
                       String status, LocalDateTime bookedAt, LocalDateTime attendedAt) {
    }

    /** Collected first, written once. See {@link #writeAll}. */
    private final List<Row> pending = new ArrayList<>();

    private void insert(long patientId, long departmentId, LocalDate date, int queueNumber,
                        String status, LocalDateTime bookedAt, LocalDateTime attendedAt) {
        pending.add(new Row(patientId, departmentId, date, queueNumber, status, bookedAt, attendedAt));
    }

    /**
     * Writes every seeded appointment in one transaction, as a single JDBC batch.
     *
     * <p>The first version inserted row by row through {@code Database.query}, which opens
     * a fresh connection per call. Six hundred rows meant six hundred connect and
     * authenticate cycles, and startup took the best part of a minute -- long enough that a
     * hosting platform's health check would give up and mark the deploy failed.
     *
     * <p>One connection and one batch sends the same rows in a handful of round trips.
     */
    private void writeAll() {
        if (pending.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            String sql = """
                    INSERT INTO appointment (patient_id, department_id, appointment_date,
                                             queue_number, status, booked_at, attended_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (department_id, appointment_date, queue_number) DO NOTHING
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (Row row : pending) {
                    ps.setLong(1, row.patientId());
                    ps.setLong(2, row.departmentId());
                    ps.setDate(3, Date.valueOf(row.date()));
                    ps.setInt(4, row.queueNumber());
                    ps.setString(5, row.status());
                    ps.setTimestamp(6, Timestamp.valueOf(row.bookedAt()));
                    ps.setTimestamp(7, row.attendedAt() == null
                            ? null : Timestamp.valueOf(row.attendedAt()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
        pending.clear();
    }

    private long countAppointments() {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM appointment");
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
