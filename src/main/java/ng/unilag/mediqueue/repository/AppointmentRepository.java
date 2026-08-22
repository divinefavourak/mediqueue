package ng.unilag.mediqueue.repository;

import ng.unilag.mediqueue.model.Appointment;
import ng.unilag.mediqueue.model.AppointmentStatus;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Storage operations for appointments and the queue derived from them.
 *
 * <p>Several methods take an explicit {@link Connection}. That is not a leak of JDBC
 * into the service layer by accident -- booking must reserve a queue number and insert
 * the row inside ONE transaction, so the caller has to be able to pass the connection
 * that transaction is running on. AppointmentService obtains it from
 * {@code Database.inTransaction} and never touches JDBC itself.
 */
public interface AppointmentRepository {

    /** Books within an existing transaction, so it can share the FOR UPDATE lock. */
    Appointment save(Connection connection, Appointment appointment);

    /**
     * Reserves the next ticket number for a department and date, locking the day's rows
     * so two concurrent bookings cannot be handed the same number.
     */
    int reserveNextQueueNumber(Connection connection, long departmentId, LocalDate date);

    Optional<Appointment> findById(long id);

    /** Every appointment belonging to one patient, newest day first. */
    List<Appointment> findByPatient(long patientId);

    /** One department's queue for one day, in ticket order, with patient names joined in. */
    List<Appointment> findDailyQueue(long departmentId, LocalDate date);

    /**
     * Counts waiting patients with a lower ticket number -- the derived queue position
     * from Project.md 4.3.
     */
    int countAheadOf(long departmentId, LocalDate date, int queueNumber);

    /** Total still waiting in a department on a date. */
    int countWaiting(long departmentId, LocalDate date);

    /** Ticket currently being seen, or 0 when nobody is in progress. */
    int currentlyServing(long departmentId, LocalDate date);

    /**
     * How fast a department is getting through its queue today.
     *
     * @param medianMinutes typical minutes between one patient being seen and the next
     * @param samples how many gaps that median was taken from
     */
    record ServicePace(double medianMinutes, int samples) {
    }

    /**
     * Measures the pace of a department's queue from the gaps between consecutive
     * patients being marked attended.
     *
     * <p>This is the only honest basis for a wait estimate in MediQueue. The obvious
     * alternative, the interval from booked_at to attended_at, mostly measures how far in
     * advance somebody booked -- days, usually -- and has almost nothing to do with time
     * spent waiting.
     *
     * <p>Empty when the day has produced too few gaps to say anything.
     */
    Optional<ServicePace> servicePace(long departmentId, LocalDate date);

    /** How many slots are already taken, for the daily capacity check. */
    int countBookedOn(long departmentId, LocalDate date);

    /** True when this patient already holds a live booking for that department and day. */
    boolean hasActiveBooking(long patientId, long departmentId, LocalDate date);

    void updateStatus(long appointmentId, AppointmentStatus status);

    /** Moves an appointment to a new date, assigning a fresh ticket for that day. */
    Appointment reschedule(Connection connection, long appointmentId, LocalDate newDate);

    long count();
}
