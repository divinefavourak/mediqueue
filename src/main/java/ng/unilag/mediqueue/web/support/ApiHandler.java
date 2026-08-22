package ng.unilag.mediqueue.web.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import ng.unilag.mediqueue.exception.AuthenticationException;
import ng.unilag.mediqueue.exception.AuthorizationException;
import ng.unilag.mediqueue.exception.MediQueueException;
import ng.unilag.mediqueue.exception.MethodNotAllowedException;
import ng.unilag.mediqueue.model.Role;

import java.io.IOException;
import java.util.Optional;

/**
 * Base class for every API endpoint: handles authentication, authorisation and error
 * translation so the concrete handlers contain only their own logic.
 *
 * <p>Two things are centralised here on purpose.
 *
 * <p><b>Authorisation.</b> Role checks happen once, in {@link #handle}, before the
 * subclass runs. Scattering them through individual handlers is how a route eventually
 * ships without one.
 *
 * <p><b>Error translation.</b> Every exception is caught and converted to a JSON reply
 * carrying the status its type declares. Nothing is swallowed: unexpected failures are
 * logged with their stack trace and answered with a generic 500, because a raw exception
 * message returned to a browser can disclose SQL, file paths and schema details.
 *
 * <p>Spring Boot port: replaced by Spring Security for the role checks and one
 * {@code @RestControllerAdvice} for the error mapping.
 */
public abstract class ApiHandler implements HttpHandler {

    protected final SessionStore sessions;

    /** Roles allowed to call this endpoint. Empty means the endpoint is public. */
    private final Role[] allowedRoles;

    protected ApiHandler(SessionStore sessions, Role... allowedRoles) {
        this.sessions = sessions;
        this.allowedRoles = allowedRoles;
    }

    /** Implemented by each endpoint. Anything thrown is translated by {@link #handle}. */
    protected abstract void process(HttpExchange exchange) throws IOException;

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        try {
            if (allowedRoles.length > 0) {
                requireRole(exchange);
            }
            process(exchange);
        } catch (MediQueueException e) {
            // A failure we raised deliberately: its type carries the right status code
            // and its message was written to be shown to a user.
            HttpExchanges.sendError(exchange, e.statusCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            HttpExchanges.sendError(exchange, 400, "Invalid request: " + e.getMessage());
        } catch (RuntimeException e) {
            // Genuinely unexpected. Log everything, disclose nothing.
            System.err.println("ERROR handling " + exchange.getRequestURI() + ": " + e);
            e.printStackTrace();
            HttpExchanges.sendError(exchange, 500,
                    "Something went wrong on our side. Please try again.");
        } finally {
            exchange.close();
        }
    }

    // ------------------------------------------------------------- session

    /** The signed-in user, or empty when there is no valid session. */
    protected Optional<SessionStore.Session> currentSession(HttpExchange exchange) {
        return HttpExchanges.cookie(exchange, SessionStore.COOKIE_NAME).flatMap(sessions::lookup);
    }

    /** The signed-in user, or a 401 if nobody is signed in. */
    protected SessionStore.Session requireSession(HttpExchange exchange) {
        return currentSession(exchange)
                .orElseThrow(() -> new AuthenticationException("Please log in to continue."));
    }

    /**
     * Enforces this endpoint's role list.
     *
     * <p>401 and 403 are kept distinct: 401 means "we do not know who you are, logging in
     * may help", 403 means "we know exactly who you are and the answer is still no".
     */
    private void requireRole(HttpExchange exchange) {
        SessionStore.Session session = requireSession(exchange);
        for (Role allowed : allowedRoles) {
            if (session.role() == allowed) {
                return;
            }
        }
        throw new AuthorizationException("Your account does not have access to this feature.");
    }

    // ------------------------------------------------------------- methods

    /** Rejects any HTTP method other than the one expected. */
    protected void requireMethod(HttpExchange exchange, String method) {
        if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
            throw new MethodNotAllowedException("Use " + method + " for this endpoint.");
        }
    }

    protected boolean isMethod(HttpExchange exchange, String method) {
        return exchange.getRequestMethod().equalsIgnoreCase(method);
    }
}
