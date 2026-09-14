package com.mockinterview.backend.exception;

/** Thrown when a guest account (see User.guest) tries to start a second interview session —
 *  guests get exactly one free attempt before they need a real account. */
public class GuestAttemptLimitException extends RuntimeException {
    public GuestAttemptLimitException() {
        super("You've used your one free guest attempt — sign up for a free account to keep practicing.");
    }
}
