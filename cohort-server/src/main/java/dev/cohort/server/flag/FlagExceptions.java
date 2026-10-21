package dev.cohort.server.flag;

import java.util.List;

public final class FlagExceptions {

    private FlagExceptions() {
    }

    public static class FlagNotFoundException extends RuntimeException {
        public FlagNotFoundException(String key) {
            super("flag '" + key + "' not found");
        }
    }

    public static class FlagConflictException extends RuntimeException {
        public FlagConflictException(String message) {
            super(message);
        }
    }

    public static class InvalidFlagException extends RuntimeException {
        private final List<String> errors;

        public InvalidFlagException(List<String> errors) {
            super("invalid flag: " + String.join("; ", errors));
            this.errors = errors;
        }

        public List<String> errors() {
            return errors;
        }
    }
}
