package ng.unilag.mediqueue.repository;

import ng.unilag.mediqueue.model.Role;
import ng.unilag.mediqueue.model.User;

import java.util.List;
import java.util.Optional;

/**
 * Storage operations for accounts.
 *
 * <p>Declared as an interface with a separate JDBC implementation on purpose. Services
 * depend only on this type, so the Spring Boot port becomes:
 *
 * <pre>
 *   public interface UserRepository extends JpaRepository&lt;User, Long&gt; {
 *       Optional&lt;User&gt; findByEmailIgnoreCase(String email);
 *   }
 * </pre>
 *
 * ...and JdbcUserRepository is deleted. Nothing above this layer changes.
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(long id);

    /** Case-insensitive, matching the LOWER(email) unique index. */
    Optional<User> findByEmail(String email);

    boolean emailExists(String email);

    List<User> findAll();

    List<User> findByRole(Role role);

    void deleteById(long id);

    long count();
}
