package ng.unilag.mediqueue.model;

import java.time.LocalDateTime;

/**
 * A person with an account. Base of the Patient / Staff / Administrator hierarchy from
 * Project.md section 3.
 *
 * <p>All three subclasses live in one `app_user` table, told apart by the `role`
 * discriminator column. That is a deliberate choice: they share every identity field
 * (name, email, credentials) and differ only in what they may do, so three near-identical
 * tables would duplicate the login logic three times.
 *
 * <p>What each role is permitted to do is expressed as overridable behaviour here rather
 * than as `if (role == ADMIN)` checks scattered through the web layer. Adding a fourth
 * role later means adding one subclass, not hunting down every conditional.
 *
 * <p>Spring Boot port: annotate with {@code @Entity}, {@code @Inheritance(strategy =
 * SINGLE_TABLE)} and {@code @DiscriminatorColumn(name = "role")}. The shape below is
 * already what JPA expects.
 */
public abstract class User {

    private Long id;
    private String fullName;
    private String email;
    private String phone;
    private String passwordHash;
    private String passwordSalt;
    private LocalDateTime createdAt;

    protected User() {
        // Required so repositories can build an instance before populating it.
    }

    protected User(Long id, String fullName, String email, String phone) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
    }

    /** The discriminator value written to the `role` column. */
    public abstract Role role();

    /** Page this user is sent to after logging in. */
    public abstract String landingPage();

    /** May call the queue board and mark patients attended or skipped (section 3.2). */
    public boolean canManageQueue() {
        return false;
    }

    /** May manage departments, staff accounts and reports (section 3.3). */
    public boolean canAdminister() {
        return false;
    }

    /** May book appointments for themselves (section 3.1). */
    public boolean canBookAppointments() {
        return false;
    }

    /** Builds the right subclass for a row's discriminator value. */
    public static User forRole(Role role) {
        return switch (role) {
            case PATIENT -> new Patient();
            case STAFF -> new Staff();
            case ADMIN -> new Administrator();
        };
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    public void setPasswordSalt(String passwordSalt) {
        this.passwordSalt = passwordSalt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Deliberately omits both credential fields. toString output ends up in logs and
     * stack traces, and a password hash printed to a console is a hash leaked.
     */
    @Override
    public String toString() {
        return role() + "{id=" + id + ", name='" + fullName + "', email='" + email + "'}";
    }
}
