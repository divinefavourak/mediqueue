package ng.unilag.mediqueue.web.handler;

import com.sun.net.httpserver.HttpExchange;
import ng.unilag.mediqueue.exception.AuthorizationException;
import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.model.Appointment;
import ng.unilag.mediqueue.model.Role;
import ng.unilag.mediqueue.service.AppointmentService;
import ng.unilag.mediqueue.web.support.ApiHandler;
import ng.unilag.mediqueue.web.support.HttpExchanges;
import ng.unilag.mediqueue.web.support.Json;
import ng.unilag.mediqueue.web.support.SessionStore;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * /api/appointments/* -- booking, listing, cancelling and rescheduling (Project.md 4.2).
 *
 * <p>Open to all three roles, because patients and staff both need it, but each operation
 * re-checks what the caller is entitled to do. The role gate on the class is coarse
 * permission; the checks inside are the real ones.
 */
public final class AppointmentHandler extends ApiHandler {

    private final AppointmentService appointments;

    public AppointmentHandler(SessionStore sessions, AppointmentService appointments) {
        super(sessions, Role.PATIENT, Role.STAFF, Role.ADMIN);
        this.appointments = appointments;
    }

    @Override
    protected void process(HttpExchange exchange) throws IOException {
        SessionStore.Session session = requireSession(exchange);
        String segment = HttpExchanges.pathSegment(exchange, 2).orElse("");

        if (isMethod(exchange, "POST") && segment.isEmpty()) {
            book(exchange, session);
        } else if (isMethod(exchange, "GET") && segment.equals("mine")) {
            listMine(exchange, session);
        } else if (isMethod(exchange, "DELETE")) {
            cancel(exchange, session);
        } else if (isMethod(exchange, "PUT")) {
            reschedule(exchange, session);
        } else {
            throw new NotFoundException("Unknown appointment endpoint.");
        }
    }

    /** POST /api/appointments -- book and receive a queue number. */
    private void book(HttpExchange exchange, SessionStore.Session session) throws IOException {
        if (session.role() != Role.PATIENT) {
            throw new AuthorizationException("Only patients can book appointments for themselves.");
        }
        Map<String, String> form = HttpExchanges.formBody(exchange);

        // The patient id comes from the session, never from the form. Trusting a
        // submitted patientId would let anyone book in another patient's name.
        Appointment appointment = appointments.book(
                session.userId(),
                HttpExchanges.requiredLong(form, "departmentId"),
                HttpExchanges.requiredDate(form, "date"));

        HttpExchanges.sendJson(exchange, 201, describe(appointment).toJson());
    }

    /** GET /api/appointments/mine. */
    private void listMine(HttpExchange exchange, SessionStore.Session session) throws IOException {
        List<Appointment> mine = appointments.findForPatient(session.userId());
        HttpExchanges.sendOk(exchange, Json.array(mine, AppointmentHandler::describe));
    }

    /** DELETE /api/appointments/{id} -- a patient withdraws (4.1, bullet 5). */
    private void cancel(HttpExchange exchange, SessionStore.Session session) throws IOException {
        long id = HttpExchanges.pathId(exchange, 2, "Appointment id");

        if (session.role() == Role.PATIENT) {
            // Ownership is enforced inside the service, so a patient cannot cancel
            // someone else's appointment by changing the id in the URL.
            appointments.cancel(id, session.userId());
        } else {
            // Staff may cancel on a patient's behalf, e.g. a phone call to the desk.
            Appointment appointment = appointments.requireAppointment(id);
            appointments.cancel(id, appointment.getPatientId());
        }
        HttpExchanges.sendOk(exchange, Json.message("message", "Appointment cancelled."));
    }

    /** PUT /api/appointments/{id}/reschedule -- staff only (3.2, bullet 6). */
    private void reschedule(HttpExchange exchange, SessionStore.Session session) throws IOException {
        if (session.role() == Role.PATIENT) {
            throw new AuthorizationException(
                    "Please contact the health centre to move an appointment.");
        }
        long id = HttpExchanges.pathId(exchange, 2, "Appointment id");
        Map<String, String> form = HttpExchanges.formBody(exchange);
        LocalDate newDate = HttpExchanges.requiredDate(form, "date");

        Appointment updated = appointments.reschedule(id, newDate);
        HttpExchanges.sendOk(exchange, describe(updated).toJson());
    }

    /** Shared serialisation, also used by the queue board. */
    static Json.JsonObject describe(Appointment appointment) {
        return Json.object()
                .put("id", appointment.getId())
                .put("departmentId", appointment.getDepartmentId())
                .put("departmentName", appointment.getDepartmentName())
                .put("date", appointment.getAppointmentDate())
                .put("queueNumber", appointment.getQueueNumber())
                .put("status", appointment.getStatus().name())
                .put("patientName", appointment.getPatientName())
                .put("patientPhone", appointment.getPatientPhone())
                .put("bookedAt", appointment.getBookedAt())
                .put("attendedAt", appointment.getAttendedAt())
                .put("cancellable", appointment.isCancellable());
    }
}
