package org.spaceapps.fire.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Python answered with an error (e.g. 422 asOf out of range): pass its status and message through. */
    @ExceptionHandler(RestClientResponseException.class)
    ProblemDetail upstreamError(RestClientResponseException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getResponseBodyAsString());
    }

    @ExceptionHandler(RestClientException.class)
    ProblemDetail upstreamDown(RestClientException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "model API unavailable: " + e.getMessage());
    }
}
