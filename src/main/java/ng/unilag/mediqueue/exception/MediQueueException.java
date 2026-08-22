package ng.unilag.mediqueue.exception;

/**
 * Base type for every failure MediQueue raises deliberately.
 *
 * <p>Unchecked on purpose: repositories and services would otherwise thread `throws`
 * clauses through every layer, which is exactly the noise that pushes people into
 * swallowing exceptions. Each subclass carries the HTTP status the web layer should
 * send, so no handler needs a chain of instanceof checks.
 */
public class MediQueueException extends RuntimeException {

    /** Exceptions inherit Serializable from Throwable; pinning this keeps the build warning-free. */
    private static final long serialVersionUID = 1L;

    public MediQueueException(String message) {
        super(message);
    }

    public MediQueueException(String message, Throwable cause) {
        super(message, cause);
    }

    /** HTTP status this failure maps to. 500 unless a subclass says otherwise. */
    public int statusCode() {
        return 500;
    }
}
