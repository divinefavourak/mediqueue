package ng.unilag.mediqueue.web.handler;

import com.sun.net.httpserver.HttpExchange;
import ng.unilag.mediqueue.exception.AuthorizationException;
import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.model.Department;
import ng.unilag.mediqueue.model.Role;
import ng.unilag.mediqueue.service.DepartmentService;
import ng.unilag.mediqueue.web.support.ApiHandler;
import ng.unilag.mediqueue.web.support.HttpExchanges;
import ng.unilag.mediqueue.web.support.Json;
import ng.unilag.mediqueue.web.support.SessionStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * /api/departments -- listing for everyone, management for administrators (3.3, 4.1).
 */
public final class DepartmentHandler extends ApiHandler {

    private final DepartmentService departments;

    public DepartmentHandler(SessionStore sessions, DepartmentService departments) {
        super(sessions, Role.PATIENT, Role.STAFF, Role.ADMIN);
        this.departments = departments;
    }

    @Override
    protected void process(HttpExchange exchange) throws IOException {
        SessionStore.Session session = requireSession(exchange);

        if (isMethod(exchange, "GET")) {
            list(exchange, session);
        } else if (isMethod(exchange, "POST")) {
            requireAdmin(session);
            create(exchange);
        } else if (isMethod(exchange, "PUT")) {
            requireAdmin(session);
            update(exchange);
        } else if (isMethod(exchange, "DELETE")) {
            requireAdmin(session);
            deactivate(exchange);
        } else {
            throw new NotFoundException("Unknown department endpoint.");
        }
    }

    /**
     * GET /api/departments
     *
     * <p>Patients see only active departments -- offering a closed clinic on the booking
     * screen would produce a booking the system then has to refuse. Staff and admins see
     * everything, including deactivated departments they may need to restore.
     */
    private void list(HttpExchange exchange, SessionStore.Session session) throws IOException {
        List<Department> visible = session.role() == Role.PATIENT
                ? departments.listActive()
                : departments.listAll();
        HttpExchanges.sendOk(exchange, Json.array(visible, DepartmentHandler::describe));
    }

    private void create(HttpExchange exchange) throws IOException {
        Map<String, String> form = HttpExchanges.formBody(exchange);
        Department created = departments.create(
                HttpExchanges.required(form, "name"),
                HttpExchanges.required(form, "opensAt"),
                HttpExchanges.required(form, "closesAt"),
                (int) HttpExchanges.requiredLong(form, "dailyCapacity"));
        HttpExchanges.sendJson(exchange, 201, describe(created).toJson());
    }

    private void update(HttpExchange exchange) throws IOException {
        long id = HttpExchanges.pathId(exchange, 2, "Department id");
        Map<String, String> form = HttpExchanges.formBody(exchange);
        Department updated = departments.update(
                id,
                HttpExchanges.required(form, "name"),
                HttpExchanges.required(form, "opensAt"),
                HttpExchanges.required(form, "closesAt"),
                (int) HttpExchanges.requiredLong(form, "dailyCapacity"),
                Boolean.parseBoolean(form.getOrDefault("active", "true")));
        HttpExchanges.sendOk(exchange, describe(updated).toJson());
    }

    /**
     * DELETE /api/departments/{id} deactivates rather than deletes.
     *
     * <p>Appointment history points at the department; removing the row would break the
     * reports in section 4.4 and destroy records a clinic is obliged to keep.
     */
    private void deactivate(HttpExchange exchange) throws IOException {
        long id = HttpExchanges.pathId(exchange, 2, "Department id");
        Department deactivated = departments.deactivate(id);
        HttpExchanges.sendOk(exchange, Json.object()
                .put("message", deactivated.getName() + " is no longer accepting appointments.")
                .putRaw("department", describe(deactivated).toJson())
                .toJson());
    }

    private void requireAdmin(SessionStore.Session session) {
        if (session.role() != Role.ADMIN) {
            throw new AuthorizationException("Only an administrator can manage departments.");
        }
    }

    static Json.JsonObject describe(Department department) {
        return Json.object()
                .put("id", department.getId())
                .put("name", department.getName())
                .put("opensAt", department.getOpensAt())
                .put("closesAt", department.getClosesAt())
                .put("dailyCapacity", department.getDailyCapacity())
                .put("active", department.isActive());
    }
}
