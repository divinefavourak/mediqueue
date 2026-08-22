package ng.unilag.mediqueue.web.handler;

import com.sun.net.httpserver.HttpExchange;
import ng.unilag.mediqueue.exception.AuthorizationException;
import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.model.Appointment;
import ng.unilag.mediqueue.model.QueuePosition;
import ng.unilag.mediqueue.model.Role;
import ng.unilag.mediqueue.service.AppointmentService;
import ng.unilag.mediqueue.service.QueueService;
import ng.unilag.mediqueue.web.support.ApiHandler;
import ng.unilag.mediqueue.web.support.HttpExchanges;
import ng.unilag.mediqueue.web.support.Json;
import ng.unilag.mediqueue.web.support.SessionStore;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * /api/queue/* -- live queue viewing and control (Project.md 4.3).
 *
 * <p>Routes:
 * <pre>
 *   GET  /api/queue/position/{appointmentId}   patient, polled every few seconds
 *   GET  /api/queue/{departmentId}?date=       staff board
 *   POST /api/queue/{appointmentId}/attend     staff
 *   POST /api/queue/{appointmentId}/skip       staff
 *   POST /api/queue/{departmentId}/call-next   staff
 * </pre>
 */
public final class QueueHandler extends ApiHandler {

    private final QueueService queues;
    private final AppointmentService appointments;

    public QueueHandler(SessionStore sessions, QueueService queues, AppointmentService appointments) {
        super(sessions, Role.PATIENT, Role.STAFF, Role.ADMIN);
        this.queues = queues;
        this.appointments = appointments;
    }

    @Override
    protected void process(HttpExchange exchange) throws IOException {
        SessionStore.Session session = requireSession(exchange);
        String first = HttpExchanges.pathSegment(exchange, 2).orElse("");
        String second = HttpExchanges.pathSegment(exchange, 3).orElse("");

        if (isMethod(exchange, "GET") && first.equals("position")) {
            position(exchange, session);
        } else if (isMethod(exchange, "GET")) {
            board(exchange, session);
        } else if (isMethod(exchange, "POST") && second.equals("attend")) {
            requireQueueControl(session);
            respond(exchange, queues.markAttended(idFrom(exchange)), "Patient marked as attended.");
        } else if (isMethod(exchange, "POST") && second.equals("skip")) {
            requireQueueControl(session);
            respond(exchange, queues.markSkipped(idFrom(exchange)), "Patient marked as absent.");
        } else if (isMethod(exchange, "POST") && second.equals("call-next")) {
            requireQueueControl(session);
            LocalDate date = HttpExchanges.optionalDate(
                    HttpExchanges.queryParams(exchange), "date", LocalDate.now());
            respond(exchange, queues.callNext(idFrom(exchange), date), "Next patient called.");
        } else {
            throw new NotFoundException("Unknown queue endpoint.");
        }
    }

    /**
     * GET /api/queue/position/{appointmentId} -- the live position a patient watches.
     *
     * <p>Ownership is checked for patients through
     * {@code AppointmentService.requireOwnedBy}. Without it, a patient could walk the id
     * space and read every appointment in the clinic, which is exactly the access
     * Project.md section 5 forbids.
     */
    private void position(HttpExchange exchange, SessionStore.Session session) throws IOException {
        long appointmentId = HttpExchanges.pathId(exchange, 3, "Appointment id");

        Appointment appointment = session.role() == Role.PATIENT
                ? appointments.requireOwnedBy(appointmentId, session.userId())
                : appointments.requireAppointment(appointmentId);

        QueuePosition position = queues.positionOf(appointment);
        HttpExchanges.sendOk(exchange, Json.object()
                .put("appointmentId", position.appointmentId())
                .put("queueNumber", position.queueNumber())
                .put("departmentName", position.departmentName())
                .put("status", position.status().name())
                .put("aheadCount", position.aheadCount())
                .put("position", position.position())
                .put("totalWaiting", position.totalWaiting())
                .put("nowServing", position.nowServing())
                .put("isNext", position.isNext())
                .put("finished", position.isFinished())
                .put("summary", position.summary())
                // Null when today has too little data to estimate honestly. The browser
                // hides the row entirely rather than showing a dash, so nothing on screen
                // ever implies a number we do not have.
                .put("estimatedMinutes", position.estimatedMinutes())
                .put("estimateText", position.estimateText())
                .toJson());
    }

    /** GET /api/queue/{departmentId}?date=YYYY-MM-DD -- the staff board. */
    private void board(HttpExchange exchange, SessionStore.Session session) throws IOException {
        requireQueueControl(session);
        long departmentId = HttpExchanges.pathId(exchange, 2, "Department id");
        Map<String, String> query = HttpExchanges.queryParams(exchange);
        LocalDate date = HttpExchanges.optionalDate(query, "date", LocalDate.now());

        List<Appointment> queue = queues.dailyQueue(departmentId, date);
        long waiting = queue.stream().filter(Appointment::isWaiting).count();

        HttpExchanges.sendOk(exchange, Json.object()
                .put("departmentId", departmentId)
                .put("date", date)
                .put("waiting", waiting)
                .put("total", queue.size())
                .putRaw("appointments", Json.array(queue, AppointmentHandler::describe))
                .toJson());
    }

    private void respond(HttpExchange exchange, Appointment appointment, String message) throws IOException {
        HttpExchanges.sendOk(exchange, Json.object()
                .put("message", message)
                .putRaw("appointment", AppointmentHandler.describe(appointment).toJson())
                .toJson());
    }

    private long idFrom(HttpExchange exchange) {
        return HttpExchanges.pathId(exchange, 2, "Id");
    }

    /**
     * Only staff and administrators may change the queue.
     *
     * <p>Uses the role from the session rather than a flag from the request, so a patient
     * cannot promote themselves by editing what the browser sends.
     */
    private void requireQueueControl(SessionStore.Session session) {
        if (session.role() == Role.PATIENT) {
            throw new AuthorizationException("Only clinic staff can manage the queue.");
        }
    }
}
