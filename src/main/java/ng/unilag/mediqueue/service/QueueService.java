package ng.unilag.mediqueue.service;

import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.exception.ValidationException;
import ng.unilag.mediqueue.model.Appointment;
import ng.unilag.mediqueue.model.AppointmentStatus;
import ng.unilag.mediqueue.model.Department;
import ng.unilag.mediqueue.model.QueuePosition;
import ng.unilag.mediqueue.repository.AppointmentRepository;
import ng.unilag.mediqueue.repository.DepartmentRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Live queue management (Project.md 4.3) -- the part of the system that exists to solve
 * the stated problem of patients waiting hours with no idea where they stand.
 *
 * <p>The central idea is that a queue position is never stored. It is computed from two
 * facts already in the appointment table: the patient's ticket number, and which lower
 * tickets are still waiting. Section 4.3 asks that positions "update automatically as
 * patients are attended to"; because nothing is cached, marking one patient attended
 * moves everyone behind them forward with no further writes at all.
 */
public final class QueueService {

    private final AppointmentRepository appointments;
    private final DepartmentRepository departments;

    public QueueService(AppointmentRepository appointments, DepartmentRepository departments) {
        this.appointments = appointments;
        this.departments = departments;
    }

    /**
     * Where one patient currently stands (4.3, bullet 4).
     *
     * <p>This is the endpoint the patient dashboard polls, so it is deliberately cheap:
     * three indexed counts and no row loading.
     */
    public QueuePosition positionOf(Appointment appointment) {
        long departmentId = appointment.getDepartmentId();
        LocalDate date = appointment.getAppointmentDate();

        int totalWaiting = appointments.countWaiting(departmentId, date);
        int nowServing = appointments.currentlyServing(departmentId, date);

        if (!appointment.isWaiting()) {
            // Attended, skipped or cancelled: no longer in line, so position is 0 rather
            // than a misleading number that would look like a place in the queue.
            return new QueuePosition(
                    appointment.getId(), appointment.getQueueNumber(), appointment.getDepartmentName(),
                    appointment.getStatus(), 0, 0, totalWaiting, nowServing);
        }

        int ahead = appointments.countAheadOf(departmentId, date, appointment.getQueueNumber());
        return new QueuePosition(
                appointment.getId(), appointment.getQueueNumber(), appointment.getDepartmentName(),
                appointment.getStatus(), ahead, ahead + 1, totalWaiting, nowServing);
    }

    /** The whole queue for a department on a day, for the staff board (4.3, bullet 1). */
    public List<Appointment> dailyQueue(long departmentId, LocalDate date) {
        departments.findById(departmentId)
                .orElseThrow(() -> new NotFoundException("That department does not exist."));
        return appointments.findDailyQueue(departmentId, date);
    }

    /** Departments a staff member can switch between on the queue board. */
    public List<Department> selectableDepartments() {
        return departments.findAll();
    }

    /** Calls the next patient forward, moving them to IN_PROGRESS (4.3, bullet 3). */
    public Appointment callNext(long departmentId, LocalDate date) {
        List<Appointment> queue = appointments.findDailyQueue(departmentId, date);
        Appointment next = queue.stream()
                .filter(Appointment::isWaiting)
                .findFirst()
                .orElseThrow(() -> new ValidationException("There is nobody waiting in this queue."));
        appointments.updateStatus(next.getId(), AppointmentStatus.IN_PROGRESS);
        next.setStatus(AppointmentStatus.IN_PROGRESS);
        return next;
    }

    /** Marks a patient as seen (4.3, bullet 3). Everyone behind them advances by one. */
    public Appointment markAttended(long appointmentId) {
        return transition(appointmentId, AppointmentStatus.ATTENDED);
    }

    /** Marks an absent patient as skipped (3.2, bullet 5). */
    public Appointment markSkipped(long appointmentId) {
        return transition(appointmentId, AppointmentStatus.SKIPPED);
    }

    /**
     * Applies a status change, refusing transitions that make no sense.
     *
     * <p>Without this guard a double-click would mark an already-attended patient
     * attended again, overwriting the original timestamp and quietly corrupting the
     * attendance report.
     */
    private Appointment transition(long appointmentId, AppointmentStatus target) {
        Appointment appointment = appointments.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("That appointment does not exist."));

        if (appointment.getStatus() == target) {
            throw new ValidationException("This patient is already marked "
                    + target.name().toLowerCase() + ".");
        }
        if (appointment.getStatus().isTerminal()) {
            throw new ValidationException("This appointment is already "
                    + appointment.getStatus().name().toLowerCase() + " and cannot be changed.");
        }

        appointments.updateStatus(appointmentId, target);
        appointment.setStatus(target);
        return appointment;
    }
}
