package ng.unilag.mediqueue.model;

/**
 * A healthcare worker who runs the daily queue for a department (Project.md 3.2).
 *
 * <p>Unlike the other two roles, staff carry a department: it scopes the queue board
 * they see and is why `department_id` exists on app_user.
 */
public class Staff extends User {

    private Long departmentId;
    /** Joined in for display; not a stored column. */
    private String departmentName;

    public Staff() {
    }

    public Staff(Long id, String fullName, String email, String phone, Long departmentId) {
        super(id, fullName, email, phone);
        this.departmentId = departmentId;
    }

    @Override
    public Role role() {
        return Role.STAFF;
    }

    @Override
    public String landingPage() {
        return "/staff/dashboard.html";
    }

    @Override
    public boolean canManageQueue() {
        return true;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }
}
