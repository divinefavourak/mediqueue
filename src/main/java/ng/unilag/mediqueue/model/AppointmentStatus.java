package ng.unilag.mediqueue.model;

/**
 * Lifecycle of an appointment.
 *
 * <pre>
 *   BOOKED ---> WAITING ---> IN_PROGRESS ---> ATTENDED   (terminal)
 *      |            |             |
 *      |            +-------------+--------> SKIPPED     (terminal, patient absent)
 *      +--------------------------------> CANCELLED      (terminal, patient withdrew)
 * </pre>
 *
 * BOOKED and WAITING are the two states that occupy a place in the queue; everything
 * else is either finished or withdrawn. {@link #occupiesQueue()} is the single source
 * of truth for that rule, so the position query and the queue board can never drift
 * apart on what "still waiting" means.
 */
public enum AppointmentStatus {
    BOOKED,
    WAITING,
    IN_PROGRESS,
    ATTENDED,
    SKIPPED,
    CANCELLED;

    /** True when the appointment still holds a place in line. */
    public boolean occupiesQueue() {
        return this == BOOKED || this == WAITING;
    }

    /** True when no further transition is possible. */
    public boolean isTerminal() {
        return this == ATTENDED || this == SKIPPED || this == CANCELLED;
    }

    /** SQL fragment listing the queue-occupying states, e.g. for an IN (...) clause. */
    public static String occupyingStatesSql() {
        return "'BOOKED', 'WAITING'";
    }

    public static AppointmentStatus fromDatabase(String value) {
        return AppointmentStatus.valueOf(value.trim().toUpperCase());
    }
}
