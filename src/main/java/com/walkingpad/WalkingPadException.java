package com.walkingpad;

/** Raised when the bridge process reports an error or communication fails. */
public class WalkingPadException extends RuntimeException {
    public WalkingPadException(String message) {
        super(message);
    }

    public WalkingPadException(String message, Throwable cause) {
        super(message, cause);
    }
}
