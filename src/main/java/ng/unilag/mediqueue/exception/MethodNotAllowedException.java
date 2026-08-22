package ng.unilag.mediqueue.exception;

/** The route exists but not for this HTTP method, e.g. a GET where a POST is required. */
public class MethodNotAllowedException extends MediQueueException {

    /** Exceptions inherit Serializable from Throwable; pinning this keeps the build warning-free. */
    private static final long serialVersionUID = 1L;

    public MethodNotAllowedException(String message) {
        super(message);
    }

    @Override
    public int statusCode() {
        return 405;
    }
}
