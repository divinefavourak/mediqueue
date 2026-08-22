package ng.unilag.mediqueue.exception;

/** A referenced record does not exist. */
public class NotFoundException extends MediQueueException {

    /** Exceptions inherit Serializable from Throwable; pinning this keeps the build warning-free. */
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }

    @Override
    public int statusCode() {
        return 404;
    }
}
