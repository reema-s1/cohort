package dev.cohort.server.web;

import dev.cohort.server.flag.FlagExceptions.FlagConflictException;
import dev.cohort.server.flag.FlagExceptions.FlagNotFoundException;
import dev.cohort.server.flag.FlagExceptions.InvalidFlagException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(FlagNotFoundException.class)
    ProblemDetail notFound(FlagNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(FlagConflictException.class)
    ProblemDetail conflict(FlagConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Two creates raced past the exists check; the primary key caught the loser. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail concurrentCreate(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "flag was created concurrently; reload and retry");
    }

    @ExceptionHandler(InvalidFlagException.class)
    ProblemDetail invalid(InvalidFlagException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid flag definition");
        problem.setProperty("errors", e.errors());
        return problem;
    }
}
