package ng.unilag.mediqueue;

import ng.unilag.mediqueue.config.AppConfig;
import ng.unilag.mediqueue.config.DemoDataSeeder;
import ng.unilag.mediqueue.config.ServiceRegistry;
import ng.unilag.mediqueue.db.SchemaInitializer;
import ng.unilag.mediqueue.exception.MediQueueException;
import ng.unilag.mediqueue.web.WebServer;

/**
 * MediQueue -- a patient queue and appointment management system for public health
 * centres.
 *
 * <p>Startup order matters and is deliberate:
 * <ol>
 *   <li>read configuration</li>
 *   <li>construct every object (ServiceRegistry)</li>
 *   <li>prove the database is reachable -- fail here, loudly, rather than on a patient's
 *       first booking</li>
 *   <li>apply the schema and seed demo data</li>
 *   <li>only then accept HTTP traffic</li>
 * </ol>
 *
 * <p>Spring Boot port: this becomes a class annotated {@code @SpringBootApplication}
 * whose main method calls {@code SpringApplication.run}. Steps 2 to 5 are what Boot does
 * for you.
 */
public final class MediQueueApplication {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();

        try {
            ServiceRegistry registry = new ServiceRegistry(config);

            System.out.println("[mediqueue] " + config.describe());
            System.out.println("[db] Connecting ...");
            registry.database().verifyConnection();

            if (config.initialiseDatabase()) {
                new SchemaInitializer(registry.database()).run();
                createFirstAccounts(config, registry);
            }

            WebServer server = new WebServer(registry);
            int port = config.serverPort();
            server.start(port);

            // Ctrl+C should close the listening socket and let in-flight requests
            // finish, rather than dropping whoever is mid-booking.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[mediqueue] Shutting down ...");
                server.stop();
            }, "shutdown"));

            banner(port);

        } catch (MediQueueException e) {
            // A failure we anticipated: show the message and nothing else. Each of these
            // messages already carries its own fix, and appending a generic "is PostgreSQL
            // running?" footer to every one of them sent people to check the database over
            // problems that had nothing to do with it.
            System.err.println();
            System.err.println("MediQueue could not start.");
            System.err.println("  " + e.getMessage());
            System.err.println();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("MediQueue failed to start: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Creates the first accounts on an empty database.
     *
     * <p>Demo accounts are opt-in and production takes the administrator path, so a
     * deployment that forgets to configure anything ends up with no accounts at all
     * rather than with a published admin login. An empty system is a nuisance; a public
     * administrator password on a system holding patient records is a breach.
     */
    private static void createFirstAccounts(AppConfig config, ServiceRegistry registry) {
        DemoDataSeeder seeder =
                new DemoDataSeeder(registry.authService(), registry.departmentService());

        if (config.seedDemoAccounts()) {
            // A public demonstration is a legitimate thing to want, so this no longer
            // refuses to start.
            //
            // An earlier version rejected demo accounts on any HTTPS deployment. That was
            // the wrong control twice over: it could not tell a coursework demo from a
            // real clinic, and the easiest way around it was to switch OFF secure cookies
            // -- so the guard nudged people toward sending session tokens over plain HTTP.
            //
            // The real hazard is somebody mistaking a demonstration for a working clinic
            // system and entering real patient details. A banner on every page prevents
            // that; refusing to boot does not. See /api/meta and the demo banner in
            // api.js.
            seeder.seedDemoAccounts();
            return;
        }
        if (config.hasAdminBootstrap()) {
            seeder.bootstrapAdministrator(config.adminEmail(), config.adminPassword());
            return;
        }
        if (registry.authService().noAccountsExist()) {
            System.out.println("""
                    [setup] The database has no accounts and none were configured.
                            Set MEDIQUEUE_ADMIN_EMAIL and MEDIQUEUE_ADMIN_PASSWORD to create
                            an administrator, or MEDIQUEUE_DEMO_SEED=true for demo accounts.
                    """);
        }
    }

    private static void banner(int port) {
        System.out.printf("""

                  MediQueue is running
                  ---------------------------------------------
                  Open   http://localhost:%d
                  Stop   Ctrl+C

                """, port);
    }
}
