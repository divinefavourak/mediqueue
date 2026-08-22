package ng.unilag.mediqueue.repository;

import java.time.LocalDate;
import java.util.List;

/**
 * The four reports required by Project.md 4.4.
 *
 * <p>This is the `Report` entity from section 8, and there is no report table behind it.
 * Every figure below is an aggregate query run when asked. Storing report rows would mean
 * they were correct only until the next appointment changed, and any bug in the writing
 * path would silently corrupt history that can no longer be recomputed.
 */
public interface ReportRepository {

    /** One row of a report: a label and the number beside it. */
    record Row(String label, long value) {
    }

    /** Attendance for a single day, broken down by outcome. */
    record DailyAttendance(
            LocalDate date,
            long booked,
            long attended,
            long skipped,
            long cancelled,
            long stillWaiting) {

        /** Share of finished appointments that were actually seen, as a percentage. */
        public double attendanceRate() {
            long finished = attended + skipped;
            return finished == 0 ? 0.0 : (attended * 100.0) / finished;
        }
    }

    /** Daily patient attendance (4.4, bullet 1). */
    DailyAttendance dailyAttendance(LocalDate date);

    /** Patient numbers by department (4.4, bullet 2). */
    List<Row> patientsByDepartment(LocalDate from, LocalDate to);

    /**
     * Peak attendance periods (4.4, bullet 3): booking volume by hour of day, which is
     * what tells a clinic manager when to roster more staff.
     */
    List<Row> peakPeriods(LocalDate from, LocalDate to);

    /** No-show statistics (4.4, bullet 4): patients called but absent, by department. */
    List<Row> noShowsByDepartment(LocalDate from, LocalDate to);

    /** Headline totals for the admin dashboard. */
    record Totals(long patients, long staff, long departments, long appointmentsToday, long waitingNow) {
    }

    Totals totals(LocalDate today);
}
