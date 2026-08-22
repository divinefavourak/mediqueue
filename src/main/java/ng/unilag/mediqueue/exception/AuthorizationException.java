package ng.unilag.mediqueue.exception;

/**
 * Authenticated, but not permitted -- a patient reaching for another patient's
 * appointment, or for a staff-only route. Distinct from AuthenticationException
 * because logging in again will not help.
 */
public class AuthorizationException extends MediQueueException {

    /** Exceptions inherit Serializable from Throwable; pinning this keeps the build warning-free. */
    private static final long serialVersionUID = 1L;

    public AuthorizationException(String message) {
        super(message);
    }

    @Override
    public int statusCode() {
        return 403;
    }
}
