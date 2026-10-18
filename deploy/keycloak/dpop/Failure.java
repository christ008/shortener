package dpop;


/** What went wrong, in words for the person running this, without a stack trace. */
class Failure extends RuntimeException {
private static final long serialVersionUID = 1L;

Failure(String message) {
    super(message);
}

Failure(String message, Throwable cause) {
    super(message, cause);
}
}
