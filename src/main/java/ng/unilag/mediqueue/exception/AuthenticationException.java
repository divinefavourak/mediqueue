package ng.unilag.mediqueue.exception;

/** No valid session, or bad credentials. The caller may retry after logging in. */
public class AuthenticationException extends MediQueueException {

    /** Exceptions inherit Serializable from Throwable; pinning this keeps the build warning-free. */
    private static final long serialVersionUID = 1L;

    public AuthenticationException(String message) {
        super(message);
    }

    @Override
    public int statusCode() {
        return 401;
    }
}
