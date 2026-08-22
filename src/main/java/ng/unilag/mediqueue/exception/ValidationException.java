package ng.unilag.mediqueue.exception;

/** Input the user can correct: a missing field, a past date, a full clinic day. */
public class ValidationException extends MediQueueException {

    /** Exceptions inherit Serializable from Throwable; pinning this keeps the build warning-free. */
    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }

    @Override
    public int statusCode() {
        return 400;
    }
}
