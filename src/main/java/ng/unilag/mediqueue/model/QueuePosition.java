package ng.unilag.mediqueue.model;

/**
 * A patient's live standing in a department queue -- the answer to "how many people are
 * still ahead of me?" (Project.md 4.3).
 *
 * <p>This is the `Queue` entity from section 8, and it is a record rather than a table
 * on purpose: every field here is computed from the appointment rows at the moment of
 * asking. Nothing about a queue position is worth storing, because it is only ever true
 * for an instant.
 *
 * <p>A record gives immutability for free, which suits a value that is built, serialised
 * to JSON, and thrown away.
 */
public record QueuePosition(
        long appointmentId,
        int queueNumber,
        String departmentName,
        AppointmentStatus status,
        /** People ahead of this patient who are still waiting. 0 means "you are next". */
        int aheadCount,
        /** 1-based place in line; 0 once the appointment is no longer waiting. */
        int position,
        /** Everyone still waiting in this department today, for context. */
        int totalWaiting,
        /** Ticket currently being seen, or 0 when nobody is in progress. */
        int nowServing) {

    /** True when this patient is at the front of the line. */
    public boolean isNext() {
        return position == 1;
    }

    /** True when the appointment has left the queue (attended, skipped or cancelled). */
    public boolean isFinished() {
        return !status.occupiesQueue();
    }

    /**
     * What the patient should do about their position -- advice, not arithmetic.
     *
     * <p>Deliberately never restates the number of people ahead. The dashboard already
     * shows that above the stub strip, and having both say "3 people ahead of you" made
     * one of them redundant. This line answers the next question instead: given where I
     * am, can I step out or should I stay put?
     */
    public String summary() {
        if (isFinished()) {
            return switch (status) {
                case ATTENDED -> "You have been attended to.";
                case SKIPPED -> "You were marked absent. Please speak to the front desk.";
                case CANCELLED -> "This appointment was cancelled.";
                default -> "This appointment is no longer in the queue.";
            };
        }
        if (isNext()) {
            return "You are next. Head to the consulting room.";
        }
        if (aheadCount <= 3) {
            return "Almost your turn. Stay close by.";
        }
        return "You have time, but stay within reach of the centre.";
    }
}
