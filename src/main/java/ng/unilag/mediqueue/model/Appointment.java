package ng.unilag.mediqueue.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A patient's booked slot in a department's queue for a given day (Project.md 4.2).
 *
 * <p>Note what is absent: there is no `position` field. Queue position is derived on
 * demand from queue_number and status (see QueueService). Storing it would mean
 * rewriting every row behind a patient each time one is attended, and any half-finished
 * update would leave the queue lying to people.
 *
 * <p>queue_number, by contrast, IS stored: it is the ticket the patient was handed and
 * must never change. Position moves; ticket number does not.
 */
public class Appointment {

    private Long id;
    private Long patientId;
    private Long departmentId;
    private LocalDate appointmentDate;
    private int queueNumber;
    private AppointmentStatus status;
    private LocalDateTime bookedAt;
    private LocalDateTime attendedAt;

    /** Joined in for display; not stored columns. */
    private String patientName;
    private String patientPhone;
    private String departmentName;

    public Appointment() {
    }

    public Appointment(Long patientId, Long departmentId, LocalDate appointmentDate,
                       int queueNumber, AppointmentStatus status) {
        this.patientId = patientId;
        this.departmentId = departmentId;
        this.appointmentDate = appointmentDate;
        this.queueNumber = queueNumber;
        this.status = status;
    }

    /** True when this appointment still holds a place in line. */
    public boolean isWaiting() {
        return status != null && status.occupiesQueue();
    }

    /** A patient may withdraw only while the slot is still live and in the future. */
    public boolean isCancellable() {
        return isWaiting() && !appointmentDate.isBefore(LocalDate.now());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public void setPatientId(Long patientId) {
        this.patientId = patientId;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public LocalDate getAppointmentDate() {
        return appointmentDate;
    }

    public void setAppointmentDate(LocalDate appointmentDate) {
        this.appointmentDate = appointmentDate;
    }

    public int getQueueNumber() {
        return queueNumber;
    }

    public void setQueueNumber(int queueNumber) {
        this.queueNumber = queueNumber;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public void setStatus(AppointmentStatus status) {
        this.status = status;
    }

    public LocalDateTime getBookedAt() {
        return bookedAt;
    }

    public void setBookedAt(LocalDateTime bookedAt) {
        this.bookedAt = bookedAt;
    }

    public LocalDateTime getAttendedAt() {
        return attendedAt;
    }

    public void setAttendedAt(LocalDateTime attendedAt) {
        this.attendedAt = attendedAt;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getPatientPhone() {
        return patientPhone;
    }

    public void setPatientPhone(String patientPhone) {
        this.patientPhone = patientPhone;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }

    @Override
    public String toString() {
        return "Appointment{id=" + id + ", dept=" + departmentId + ", date=" + appointmentDate
                + ", queueNo=" + queueNumber + ", status=" + status + "}";
    }
}
