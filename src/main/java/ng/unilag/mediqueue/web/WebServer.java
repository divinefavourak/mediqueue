package ng.unilag.mediqueue.web;

import com.sun.net.httpserver.HttpServer;
import ng.unilag.mediqueue.config.ServiceRegistry;
import ng.unilag.mediqueue.web.handler.AppointmentHandler;
import ng.unilag.mediqueue.web.handler.AuthHandler;
import ng.unilag.mediqueue.web.handler.DepartmentHandler;
import ng.unilag.mediqueue.web.handler.MetaHandler;
import ng.unilag.mediqueue.web.handler.QueueHandler;
import ng.unilag.mediqueue.web.handler.ReportHandler;
import ng.unilag.mediqueue.web.handler.StaticFileHandler;
import ng.unilag.mediqueue.web.handler.UserHandler;
import ng.unilag.mediqueue.web.support.SessionStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Starts the HTTP server and maps URL prefixes to handlers.
 *
 * <p>Uses {@code com.sun.net.httpserver.HttpServer}, which ships inside the JDK. It is a
 * genuine HTTP/1.1 server -- no Tomcat, no framework, no downloads. What it does not do
 * is routing, so the context map below is MediQueue's router: HttpServer matches the
 * longest registered prefix and each handler reads the rest of the path itself.
 *
 * <p>Spring Boot port: delete this class. Boot starts embedded Tomcat and builds the
 * route table from {@code @RequestMapping} annotations.
 */
public final class WebServer {

    /**
     * Requests are handled on a small pool rather than one thread each. Every request is
     * short and database-bound, and an unbounded pool would let a burst of traffic open
     * more PostgreSQL connections than the server accepts.
     */
    private static final int THREAD_POOL_SIZE = 16;

    private final ServiceRegistry registry;
    private HttpServer server;

    public WebServer(ServiceRegistry registry) {
        this.registry = registry;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        SessionStore sessions = registry.sessionStore();

        // --- API routes. Longest matching prefix wins, so /api/auth beats /.
        server.createContext("/api/auth",
                new AuthHandler(sessions, registry.authService(), registry.config().secureCookies()));
        server.createContext("/api/appointments",
                new AppointmentHandler(sessions, registry.appointmentService()));
        server.createContext("/api/queue",
                new QueueHandler(sessions, registry.queueService(), registry.appointmentService()));
        server.createContext("/api/departments",
                new DepartmentHandler(sessions, registry.departmentService()));
        server.createContext("/api/reports", new ReportHandler(sessions, registry.reportService()));
        server.createContext("/api/meta",
                new MetaHandler(sessions, registry.config().seedDemoAccounts()));
        server.createContext("/api/users", new UserHandler(sessions, registry.authService()));

        // --- Everything else is a page, stylesheet or script.
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE));
        server.start();

        startSessionCleanup();
    }

    /**
     * Clears expired sessions hourly.
     *
     * <p>Without it the session map only ever grows: every login adds an entry, and
     * nothing removes the ones nobody returns to. A daemon thread so it can never keep
     * the JVM alive at shutdown.
     */
    private void startSessionCleanup() {
        var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(() -> {
            int purged = registry.sessionStore().purgeExpired();
            if (purged > 0) {
                System.out.println("[sessions] Cleared " + purged + " expired session(s).");
            }
        }, 1, 1, TimeUnit.HOURS);
    }

    /** Stops the server, letting in-flight requests finish. */
    public void stop() {
        if (server == null) {
            return;
        }
        server.stop(2);
        if (server.getExecutor() instanceof ThreadPoolExecutor pool) {
            pool.shutdown();
        }
    }
}
