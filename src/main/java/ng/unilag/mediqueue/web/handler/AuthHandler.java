package ng.unilag.mediqueue.web.handler;

import com.sun.net.httpserver.HttpExchange;
import ng.unilag.mediqueue.exception.AuthenticationException;
import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.model.Staff;
import ng.unilag.mediqueue.model.User;
import ng.unilag.mediqueue.service.AuthService;
import ng.unilag.mediqueue.web.support.ApiHandler;
import ng.unilag.mediqueue.web.support.HttpExchanges;
import ng.unilag.mediqueue.web.support.Json;
import ng.unilag.mediqueue.web.support.SessionStore;

import java.io.IOException;
import java.util.Map;

/**
 * /api/auth/* -- registration, login, logout and "who am I" (Project.md 4.1).
 *
 * <p>Public by design: these are the endpoints someone must be able to reach before they
 * have a session at all.
 *
 * <p>Spring Boot port: becomes {@code @RestController @RequestMapping("/api/auth")}.
 */
public final class AuthHandler extends ApiHandler {

    private final AuthService authService;
    /** Whether session cookies carry the Secure flag; comes from configuration. */
    private final boolean secureCookies;

    public AuthHandler(SessionStore sessions, AuthService authService, boolean secureCookies) {
        super(sessions); // no role restriction
        this.authService = authService;
        this.secureCookies = secureCookies;
    }

    @Override
    protected void process(HttpExchange exchange) throws IOException {
        String action = HttpExchanges.pathSegment(exchange, 2).orElse("");
        switch (action) {
            case "register" -> register(exchange);
            case "login" -> login(exchange);
            case "logout" -> logout(exchange);
            case "me" -> me(exchange);
            default -> throw new NotFoundException("Unknown auth endpoint: " + action);
        }
    }

    /** POST /api/auth/register -- patient self-registration. */
    private void register(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        Map<String, String> form = HttpExchanges.formBody(exchange);

        User user = authService.registerPatient(
                HttpExchanges.required(form, "fullName"),
                HttpExchanges.required(form, "email"),
                form.get("phone"),
                HttpExchanges.required(form, "password"));

        // Registering signs the patient straight in, so they never have to type the
        // password twice to reach the booking screen.
        startSession(exchange, user);
        HttpExchanges.sendJson(exchange, 201, describe(user).toJson());
    }

    /** POST /api/auth/login. */
    private void login(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        Map<String, String> form = HttpExchanges.formBody(exchange);

        User user = authService.authenticate(
                HttpExchanges.required(form, "email"),
                HttpExchanges.required(form, "password"));

        startSession(exchange, user);
        HttpExchanges.sendOk(exchange, describeWithLanding(user).toJson());
    }

    /** POST /api/auth/logout. */
    private void logout(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        HttpExchanges.cookie(exchange, SessionStore.COOKIE_NAME).ifPresent(sessions::invalidate);
        HttpExchanges.clearSessionCookie(exchange, SessionStore.COOKIE_NAME, secureCookies);
        HttpExchanges.sendOk(exchange, Json.message("message", "Signed out."));
    }

    /**
     * GET /api/auth/me -- who is signed in.
     *
     * <p>Every page calls this on load to decide what to render, and to bounce anyone
     * without a session back to the login screen.
     */
    private void me(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        SessionStore.Session session = currentSession(exchange)
                .orElseThrow(() -> new AuthenticationException("Not signed in."));
        User user = authService.requireUser(session.userId());
        HttpExchanges.sendOk(exchange, describeWithLanding(user).toJson());
    }

    private void startSession(HttpExchange exchange, User user) {
        String token = sessions.create(user.getId(), user.getFullName(), user.role());
        HttpExchanges.setSessionCookie(
                exchange, SessionStore.COOKIE_NAME, token, SessionStore.lifetimeSeconds(), secureCookies);
    }

    private Json.JsonObject describeWithLanding(User user) {
        return describe(user).put("landingPage", user.landingPage());
    }

    /**
     * Serialises a user for the browser.
     *
     * <p>Note what is not here: passwordHash and passwordSalt. Credential material must
     * never leave the server, not even to an authenticated owner, and the safest way to
     * guarantee that is to build the JSON field by field rather than reflecting over the
     * object.
     */
    static Json.JsonObject describe(User user) {
        Json.JsonObject json = Json.object()
                .put("id", user.getId())
                .put("fullName", user.getFullName())
                .put("email", user.getEmail())
                .put("phone", user.getPhone())
                .put("role", user.role().name())
                .put("canManageQueue", user.canManageQueue())
                .put("canAdminister", user.canAdminister())
                .put("canBookAppointments", user.canBookAppointments());
        if (user instanceof Staff staff) {
            json.put("departmentId", staff.getDepartmentId())
                .put("departmentName", staff.getDepartmentName());
        }
        return json;
    }
}
