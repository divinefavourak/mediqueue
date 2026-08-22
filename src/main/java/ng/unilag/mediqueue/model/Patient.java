package ng.unilag.mediqueue.model;

/**
 * Someone who books appointments and watches their queue position (Project.md 3.1).
 *
 * <p>The most restricted role: it grants nothing beyond booking, and every read of an
 * appointment is additionally checked against the owner's id in AppointmentService.
 */
public class Patient extends User {

    public Patient() {
    }

    public Patient(Long id, String fullName, String email, String phone) {
        super(id, fullName, email, phone);
    }

    @Override
    public Role role() {
        return Role.PATIENT;
    }

    @Override
    public String landingPage() {
        return "/patient/dashboard.html";
    }

    @Override
    public boolean canBookAppointments() {
        return true;
    }
}
