package org.filteredpush.bdq_workbench.app;

/** Base runtime exception for the workbench application. */
public class AppException extends RuntimeException {
    public AppException(String message) {
        super(message);
    }

    public AppException(String message, Throwable cause) {
        super(message, cause);
    }
}
