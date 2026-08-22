package ng.unilag.mediqueue.model;

/**
 * The three actor types from Project.md section 3.
 *
 * <p>Stored as the `role` discriminator column, so this enum decides which User
 * subclass a database row becomes.
 */
public enum Role {
    PATIENT,
    STAFF,
    ADMIN;

    public static Role fromDatabase(String value) {
        return Role.valueOf(value.trim().toUpperCase());
    }
}
