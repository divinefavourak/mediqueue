package ng.unilag.mediqueue.model;

/**
 * Manages staff accounts, departments and reports (Project.md 3.3).
 *
 * <p>Also inherits queue management: an administrator who cannot step in and clear a
 * stuck queue would be an odd kind of administrator.
 */
public class Administrator extends User {

    public Administrator() {
    }

    public Administrator(Long id, String fullName, String email, String phone) {
        super(id, fullName, email, phone);
    }

    @Override
    public Role role() {
        return Role.ADMIN;
    }

    @Override
    public String landingPage() {
        return "/admin/dashboard.html";
    }

    @Override
    public boolean canManageQueue() {
        return true;
    }

    @Override
    public boolean canAdminister() {
        return true;
    }
}
