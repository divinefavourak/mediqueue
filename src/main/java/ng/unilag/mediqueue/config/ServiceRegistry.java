package ng.unilag.mediqueue.config;

import ng.unilag.mediqueue.db.Database;
import ng.unilag.mediqueue.repository.AppointmentRepository;
import ng.unilag.mediqueue.repository.DepartmentRepository;
import ng.unilag.mediqueue.repository.JdbcAppointmentRepository;
import ng.unilag.mediqueue.repository.JdbcDepartmentRepository;
import ng.unilag.mediqueue.repository.JdbcReportRepository;
import ng.unilag.mediqueue.repository.JdbcUserRepository;
import ng.unilag.mediqueue.repository.ReportRepository;
import ng.unilag.mediqueue.repository.UserRepository;
import ng.unilag.mediqueue.service.AppointmentService;
import ng.unilag.mediqueue.service.AuthService;
import ng.unilag.mediqueue.service.DepartmentService;
import ng.unilag.mediqueue.service.PasswordEncoder;
import ng.unilag.mediqueue.service.QueueService;
import ng.unilag.mediqueue.service.ReportService;
import ng.unilag.mediqueue.web.support.SessionStore;

/**
 * Builds every object in the application, once, in dependency order.
 *
 * <p>This class is a dependency injection container written by hand. Spring does exactly
 * what happens below -- construct the repositories, pass them into the services, pass
 * those wherever they are needed -- it simply discovers the order by reflection instead
 * of being told. Seeing the wiring spelled out makes it obvious what {@code @Autowired}
 * is actually doing.
 *
 * <p>Notice that only the four lines creating {@code Jdbc*Repository} name a concrete
 * class. Every service below receives an interface. That is what makes the Spring Boot
 * port a deletion rather than a rewrite: this file disappears, Spring supplies JPA
 * implementations of those same interfaces, and no service changes at all.
 */
public final class ServiceRegistry {

    private final AppConfig config;
    private final Database database;

    private final AuthService authService;
    private final DepartmentService departmentService;
    private final AppointmentService appointmentService;
    private final QueueService queueService;
    private final ReportService reportService;
    private final SessionStore sessionStore;

    public ServiceRegistry(AppConfig config) {
        this.config = config;
        this.database = new Database(config);

        // Infrastructure -- the only place concrete JDBC classes are named.
        UserRepository userRepository = new JdbcUserRepository(database);
        DepartmentRepository departmentRepository = new JdbcDepartmentRepository(database);
        AppointmentRepository appointmentRepository = new JdbcAppointmentRepository(database);
        ReportRepository reportRepository = new JdbcReportRepository(database);

        // Business logic -- depends on interfaces above, never on implementations.
        PasswordEncoder passwordEncoder = new PasswordEncoder(config.pbkdf2Iterations());
        this.authService = new AuthService(userRepository, passwordEncoder);
        this.departmentService = new DepartmentService(departmentRepository);
        this.appointmentService = new AppointmentService(
                database, appointmentRepository, departmentRepository);
        this.queueService = new QueueService(appointmentRepository, departmentRepository);
        this.reportService = new ReportService(reportRepository);

        this.sessionStore = new SessionStore();
    }

    public AppConfig config() {
        return config;
    }

    public Database database() {
        return database;
    }

    public AuthService authService() {
        return authService;
    }

    public DepartmentService departmentService() {
        return departmentService;
    }

    public AppointmentService appointmentService() {
        return appointmentService;
    }

    public QueueService queueService() {
        return queueService;
    }

    public ReportService reportService() {
        return reportService;
    }

    public SessionStore sessionStore() {
        return sessionStore;
    }
}
