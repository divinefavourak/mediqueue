package ng.unilag.mediqueue.web.handler;

import com.sun.net.httpserver.HttpExchange;
import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.exception.ValidationException;
import ng.unilag.mediqueue.model.Role;
import ng.unilag.mediqueue.model.User;
import ng.unilag.mediqueue.service.AuthService;
import ng.unilag.mediqueue.web.support.ApiHandler;
import ng.unilag.mediqueue.web.support.HttpExchanges;
import ng.unilag.mediqueue.web.support.Json;
import ng.unilag.mediqueue.web.support.SessionStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * /api/users -- staff and administrator account management (Project.md 3.3).
 *
 * <p>Administrators only. This is the endpoint that creates privileged accounts, so it is
 * the one place where a missing role check would let anyone mint themselves an admin
 * login.
 */
public final class UserHandler extends ApiHandler {

    private final AuthService authService;

    public UserHandler(SessionStore sessions, AuthService authService) {
        super(sessions, Role.ADMIN);
        this.authService = authService;
    }

    @Override
    protected void process(HttpExchange exchange) throws IOException {
        if (isMethod(exchange, "GET")) {
            list(exchange);
        } else if (isMethod(exchange, "POST")) {
            create(exchange);
        } else if (isMethod(exchange, "DELETE")) {
            delete(exchange);
        } else {
            throw new NotFoundException("Unknown users endpoint.");
        }
    }

    /** GET /api/users?role=STAFF -- all accounts, optionally filtered. */
    private void list(HttpExchange exchange) throws IOException {
        Map<String, String> query = HttpExchanges.queryParams(exchange);
        String roleFilter = query.get("role");

        List<User> users = (roleFilter == null || roleFilter.isBlank())
                ? authService.listAll()
                : authService.listByRole(parseRole(roleFilter));

        HttpExchanges.sendOk(exchange, Json.array(users, AuthHandler::describe));
    }

    /** POST /api/users -- create a staff or administrator account. */
    private void create(HttpExchange exchange) throws IOException {
        Map<String, String> form = HttpExchanges.formBody(exchange);
        Role role = parseRole(HttpExchanges.required(form, "role"));

        String departmentId = form.get("departmentId");
        User created = authService.createStaffAccount(
                role,
                HttpExchanges.required(form, "fullName"),
                HttpExchanges.required(form, "email"),
                form.get("phone"),
                HttpExchanges.required(form, "password"),
                (departmentId == null || departmentId.isBlank()) ? null : Long.valueOf(departmentId));

        HttpExchanges.sendJson(exchange, 201, AuthHandler.describe(created).toJson());
    }

    /** DELETE /api/users/{id}. */
    private void delete(HttpExchange exchange) throws IOException {
        long id = HttpExchanges.pathId(exchange, 2, "User id");
        SessionStore.Session session = requireSession(exchange);

        // An administrator deleting their own account would lock the clinic out of
        // department and staff management entirely.
        if (session.userId() == id) {
            throw new ValidationException("You cannot delete your own account.");
        }
        authService.deleteAccount(id);
        HttpExchanges.sendOk(exchange, Json.message("message", "Account deleted."));
    }

    private Role parseRole(String raw) {
        try {
            return Role.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Role must be PATIENT, STAFF or ADMIN.");
        }
    }
}
