package ng.unilag.mediqueue.config;

import ng.unilag.mediqueue.model.Department;
import ng.unilag.mediqueue.model.Role;
import ng.unilag.mediqueue.service.AuthService;
import ng.unilag.mediqueue.service.DepartmentService;

import java.util.List;

/**
 * Creates the first accounts when MediQueue starts against an empty database.
 *
 * <p>There are two quite different first runs, and conflating them is how a demo password
 * ends up on the public internet:
 *
 * <ul>
 *   <li>{@link #seedDemoAccounts()} -- three accounts sharing a password printed in the
 *       README. For a laptop or a classroom demonstration, never for a real deployment.</li>
 *   <li>{@link #bootstrapAdministrator} -- one administrator whose password comes from the
 *       environment. This is the production path.</li>
 * </ul>
 *
 * <p>Neither can live in seed.sql, because passwords must be hashed with PBKDF2 by the
 * application; a hash written by hand into SQL would either be wrong or would mean
 * committing a real credential to the repository.
 *
 * <p>Both run only when no accounts exist at all, so neither overwrites real data nor
 * resurrects an account an administrator deleted.
 */
public final class DemoDataSeeder {

    /** Demo password, published in the README. Coursework only. */
    public static final String DEMO_PASSWORD = "mediqueue123";

    private final AuthService authService;
    private final DepartmentService departmentService;

    public DemoDataSeeder(AuthService authService, DepartmentService departmentService) {
        this.authService = authService;
        this.departmentService = departmentService;
    }

    /**
     * Creates the three published demo accounts.
     *
     * <p>The warning is deliberately loud. Anyone who sees it on a server log should treat
     * the instance as compromised, because the administrator password is public.
     */
    public void seedDemoAccounts() {
        if (!authService.noAccountsExist()) {
            return;
        }

        authService.createStaffAccount(Role.ADMIN,
                "System Administrator", "admin@mediqueue.ng", "08030000001", DEMO_PASSWORD, null);

        // Attach the demo nurse to whichever department sorts first, so the account always
        // points at one that actually exists.
        List<Department> departments = departmentService.listActive();
        if (!departments.isEmpty()) {
            authService.createStaffAccount(Role.STAFF,
                    "Nurse Amaka Obi", "nurse@mediqueue.ng", "08030000002",
                    DEMO_PASSWORD, departments.get(0).getId());
        }

        authService.registerPatient("Chidi Okafor", "patient@mediqueue.ng", "08030000003", DEMO_PASSWORD);

        System.out.println("""

                ***********************************************************
                *  DEMO ACCOUNTS CREATED -- password: %s
                *     admin@mediqueue.ng     administrator
                *     nurse@mediqueue.ng     healthcare staff
                *     patient@mediqueue.ng   patient
                *
                *  This password is published in the README. If you are
                *  seeing this on a server anyone else can reach, stop and
                *  set MEDIQUEUE_DEMO_SEED=false.
                ***********************************************************
                """.formatted(DEMO_PASSWORD));
    }

    /**
     * Creates a single real administrator from configuration, for a fresh deployment.
     *
     * <p>The password is never logged, and there is no way to read it back afterwards --
     * it is hashed on the way in like any other.
     *
     * @return true when an account was created
     */
    public boolean bootstrapAdministrator(String email, String password) {
        if (!authService.noAccountsExist()) {
            return false;
        }
        authService.createStaffAccount(Role.ADMIN, "Administrator", email, null, password, null);
        System.out.println("[setup] Administrator account created for " + email
                + ". Sign in and change the password.");
        return true;
    }
}
