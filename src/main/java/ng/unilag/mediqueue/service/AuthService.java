package ng.unilag.mediqueue.service;

import ng.unilag.mediqueue.exception.AuthenticationException;
import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.exception.ValidationException;
import ng.unilag.mediqueue.model.Administrator;
import ng.unilag.mediqueue.model.Patient;
import ng.unilag.mediqueue.model.Role;
import ng.unilag.mediqueue.model.Staff;
import ng.unilag.mediqueue.model.User;
import ng.unilag.mediqueue.repository.UserRepository;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Registration, login and account management (Project.md 4.1, 3.3).
 *
 * <p>Depends only on {@link UserRepository} and {@link PasswordEncoder}, both passed to
 * the constructor. Nothing here knows about HTTP or JDBC, which is what lets the class
 * move to Spring Boot untouched -- add {@code @Service}, and Spring supplies the same two
 * constructor arguments the ServiceRegistry supplies today.
 */
public final class AuthService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** Public self-registration. Always creates a Patient (4.1, bullet 1). */
    public Patient registerPatient(String fullName, String email, String phone, String rawPassword) {
        return (Patient) createUser(Role.PATIENT, fullName, email, phone, rawPassword, null);
    }

    /** Staff and admin accounts are created by an administrator, never self-served (3.3). */
    public User createStaffAccount(Role role, String fullName, String email, String phone,
                                   String rawPassword, Long departmentId) {
        if (role == Role.PATIENT) {
            throw new ValidationException("Use patient registration to create a patient account.");
        }
        if (role == Role.STAFF && departmentId == null) {
            throw new ValidationException("Staff must be assigned to a department.");
        }
        return createUser(role, fullName, email, phone, rawPassword, departmentId);
    }

    private User createUser(Role role, String fullName, String email, String phone,
                            String rawPassword, Long departmentId) {
        validateName(fullName);
        validateEmail(email);
        validatePassword(rawPassword);

        String normalisedEmail = email.trim().toLowerCase();
        if (users.emailExists(normalisedEmail)) {
            throw new ValidationException("An account with that email already exists.");
        }

        User user = switch (role) {
            case PATIENT -> new Patient(null, fullName.trim(), normalisedEmail, cleanPhone(phone));
            case ADMIN -> new Administrator(null, fullName.trim(), normalisedEmail, cleanPhone(phone));
            case STAFF -> new Staff(null, fullName.trim(), normalisedEmail, cleanPhone(phone), departmentId);
        };

        String salt = passwordEncoder.newSalt();
        user.setPasswordSalt(salt);
        user.setPasswordHash(passwordEncoder.hash(rawPassword, salt));
        return users.save(user);
    }

    /**
     * Verifies credentials and returns the user.
     *
     * <p>The same message is returned whether the email is unknown or the password is
     * wrong. Distinguishing them would confirm which emails hold accounts, letting anyone
     * enumerate the patient list one address at a time.
     */
    public User authenticate(String email, String rawPassword) {
        if (email == null || email.isBlank() || rawPassword == null || rawPassword.isEmpty()) {
            throw new ValidationException("Email and password are required.");
        }
        User user = users.findByEmail(email.trim())
                .orElseThrow(() -> new AuthenticationException("Incorrect email or password."));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordSalt(), user.getPasswordHash())) {
            throw new AuthenticationException("Incorrect email or password.");
        }
        return user;
    }

    public User requireUser(long id) {
        return users.findById(id)
                .orElseThrow(() -> new NotFoundException("No account with id " + id + "."));
    }

    public List<User> listAll() {
        return users.findAll();
    }

    public List<User> listByRole(Role role) {
        return users.findByRole(role);
    }

    public void deleteAccount(long id) {
        requireUser(id);
        users.deleteById(id);
    }

    public boolean noAccountsExist() {
        return users.count() == 0;
    }

    // ------------------------------------------------------------- validation

    private void validateName(String fullName) {
        if (fullName == null || fullName.trim().length() < 2) {
            throw new ValidationException("Please enter your full name.");
        }
        if (fullName.trim().length() > 120) {
            throw new ValidationException("Name must be 120 characters or fewer.");
        }
    }

    private void validateEmail(String email) {
        if (email == null || !EMAIL.matcher(email.trim()).matches()) {
            throw new ValidationException("Please enter a valid email address.");
        }
    }

    /**
     * Length is the requirement that actually resists guessing. Forcing symbols and
     * mixed case mostly produces "Password1!", which is no harder to crack and much
     * harder to remember.
     */
    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (rawPassword.length() > 200) {
            // Bounded so nobody can force expensive hashing with a giant input.
            throw new ValidationException("Password must be 200 characters or fewer.");
        }
    }

    private String cleanPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.length() > 20) {
            throw new ValidationException("Phone number must be 20 characters or fewer.");
        }
        return trimmed;
    }
}
