package ng.unilag.mediqueue.service;

import ng.unilag.mediqueue.db.Database;
import ng.unilag.mediqueue.exception.AuthorizationException;
import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.exception.ValidationException;
import ng.unilag.mediqueue.model.Appointment;
import ng.unilag.mediqueue.model.AppointmentStatus;
import ng.unilag.mediqueue.model.Department;
import ng.unilag.mediqueue.repository.AppointmentRepository;
import ng.unilag.mediqueue.repository.DepartmentRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Booking, cancelling and rescheduling appointments (Project.md 4.1, 4.2).
 *
 * <p>Holds the rules that Project.md section 10 states as constraints: bookings must fall
 * on a day the department is open, must not exceed its daily capacity, and a patient may
 * touch only their own appointments.
 */
public final class AppointmentService {

    /** How far ahead a patient may book. Beyond this, clinic rosters are guesswork. */
    private static final int MAX_DAYS_AHEAD = 60;

    private final Database database;
    private final AppointmentRepository appointments;
    private final DepartmentRepository departments;

    public AppointmentService(Database database,
                              AppointmentRepository appointments,
                              DepartmentRepository departments) {
        this.database = database;
        this.appointments = appointments;
        this.departments = departments;
    }

    /**
     * Books an appointment and assigns its queue number (4.2, bullet 3).
     *
     * <p>The whole operation runs in one transaction. Reserving a ticket number and
     * inserting the row must be atomic: if they were separate, two patients could be
     * handed the same number, and a failure between them would burn a number and leave a
     * gap in the queue.
     */
    public Appointment book(long patientId, long departmentId, LocalDate date) {
        Department department = departments.findById(departmentId)
                .orElseThrow(() -> new NotFoundException("That department does not exist."));

        validateBookingRequest(patientId, department, date);

        return database.inTransaction(connection -> {
            // Re-check capacity inside the transaction. Checking outside it would let two
            // patients both pass the check on the last remaining slot.
            int taken = appointments.countBookedOn(departmentId, date);
            if (taken >= department.getDailyCapacity()) {
                throw new ValidationException(
                        department.getName() + " is fully booked on " + date + ". Please choose another day.");
            }

            int queueNumber = appointments.reserveNextQueueNumber(connection, departmentId, date);
            Appointment appointment = new Appointment(
                    patientId, departmentId, date, queueNumber, AppointmentStatus.BOOKED);
            Appointment saved = appointments.save(connection, appointment);
            saved.setDepartmentName(department.getName());
            return saved;
        });
    }

    private void validateBookingRequest(long patientId, Department department, LocalDate date) {
        if (!department.isActive()) {
            throw new ValidationException(department.getName() + " is not accepting appointments at the moment.");
        }
        if (date.isBefore(LocalDate.now())) {
            throw new ValidationException("You cannot book an appointment in the past.");
        }
        if (date.isAfter(LocalDate.now().plusDays(MAX_DAYS_AHEAD))) {
            throw new ValidationException("Appointments can only be booked up to "
                    + MAX_DAYS_AHEAD + " days ahead.");
        }
        // Section 10: patients may only book during available clinic schedules.
        if (date.getDayOfWeek().getValue() > 5) {
            throw new ValidationException("The clinic is closed at weekends. Please choose a weekday.");
        }
        if (appointments.hasActiveBooking(patientId, department.getId(), date)) {
            throw new ValidationException(
                    "You already have an appointment with " + department.getName() + " on that day.");
        }
    }

    /** A patient's own appointments (4.1, bullet 4). */
    public List<Appointment> findForPatient(long patientId) {
        return appointments.findByPatient(patientId);
    }

    /**
     * Loads an appointment, confirming the caller is allowed to see it.
     *
     * <p>This is the check behind Project.md section 5, "patients can only access their
     * own appointment information". Without it, changing the id in the URL would expose
     * another patient's medical appointment -- the flaw class known as insecure direct
     * object reference, and the most common serious bug in student web projects.
     */
    public Appointment requireOwnedBy(long appointmentId, long patientId) {
        Appointment appointment = requireAppointment(appointmentId);
        if (appointment.getPatientId() != patientId) {
            // Deliberately not "this belongs to someone else" -- that would confirm the
            // appointment exists, which is itself information the caller should not get.
            throw new AuthorizationException("You do not have access to that appointment.");
        }
        return appointment;
    }

    public Appointment requireAppointment(long appointmentId) {
        return appointments.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("That appointment does not exist."));
    }

    /** Cancels a patient's own appointment before its scheduled time (4.1, bullet 5). */
    public void cancel(long appointmentId, long patientId) {
        Appointment appointment = requireOwnedBy(appointmentId, patientId);
        if (!appointment.isCancellable()) {
            throw new ValidationException(
                    appointment.getStatus() == AppointmentStatus.CANCELLED
                            ? "That appointment was already cancelled."
                            : "This appointment can no longer be cancelled.");
        }
        appointments.updateStatus(appointmentId, AppointmentStatus.CANCELLED);
    }

    /** Staff reschedule an appointment to another day (4.2, bullet 2). */
    public Appointment reschedule(long appointmentId, LocalDate newDate) {
        Appointment existing = requireAppointment(appointmentId);
        if (existing.getStatus().isTerminal()) {
            throw new ValidationException("A " + existing.getStatus().name().toLowerCase()
                    + " appointment cannot be rescheduled.");
        }
        if (newDate.isBefore(LocalDate.now())) {
            throw new ValidationException("You cannot reschedule to a date in the past.");
        }
        Department department = departments.findById(existing.getDepartmentId())
                .orElseThrow(() -> new NotFoundException("That department no longer exists."));
        if (appointments.countBookedOn(existing.getDepartmentId(), newDate) >= department.getDailyCapacity()) {
            throw new ValidationException(department.getName() + " is fully booked on " + newDate + ".");
        }
        return database.inTransaction(connection ->
                appointments.reschedule(connection, appointmentId, newDate));
    }

    /** How many slots remain in a department on a date, for the booking screen. */
    public int remainingCapacity(Department department, LocalDate date) {
        int taken = appointments.countBookedOn(department.getId(), date);
        return Math.max(0, department.getDailyCapacity() - taken);
    }
}
