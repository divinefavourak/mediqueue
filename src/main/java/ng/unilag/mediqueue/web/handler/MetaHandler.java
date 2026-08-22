package ng.unilag.mediqueue.web.handler;

import com.sun.net.httpserver.HttpExchange;
import ng.unilag.mediqueue.web.support.ApiHandler;
import ng.unilag.mediqueue.web.support.HttpExchanges;
import ng.unilag.mediqueue.web.support.Json;
import ng.unilag.mediqueue.web.support.SessionStore;

import java.io.IOException;

/**
 * /api/meta -- what every page needs to know before anyone signs in.
 *
 * <p>Public on purpose. The demonstration banner has to appear on the sign-in and
 * registration pages too, and those are exactly the pages nobody has a session on yet.
 *
 * <p>Carries no patient data and nothing an attacker gains from: whether an instance is a
 * demonstration is already obvious from the banner it renders.
 */
public final class MetaHandler extends ApiHandler {

    private final boolean demoMode;

    public MetaHandler(SessionStore sessions, boolean demoMode) {
        super(sessions); // no role restriction
        this.demoMode = demoMode;
    }

    @Override
    protected void process(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        HttpExchanges.sendOk(exchange, Json.object()
                .put("demoMode", demoMode)
                .toJson());
    }
}
