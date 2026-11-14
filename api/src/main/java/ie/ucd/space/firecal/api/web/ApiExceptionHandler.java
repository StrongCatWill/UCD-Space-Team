package ie.ucd.space.firecal.api.web;

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.time.DateTimeException;
import ie.ucd.space.firecal.contract.api.ApiError;
import ie.ucd.space.firecal.contract.api.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Every error is HTTP status + {"code", "message"} (contract ApiError). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        String msg = "malformed request body";
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof DateTimeException
                    || (c instanceof MismatchedInputException m && m.getTargetType() == YearMonth.class)) {
                msg = "months must be YYYY-MM";
                break;
            }
            if (c instanceof InvalidTypeIdException) {
                msg = "aoi.type must be one of demo | polygon | circle";
                break;
            }
            if (c.getCause() == c) break;
        }
        return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_INPUT, msg);
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> invalid(Exception e) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_INPUT, "invalid request parameter");
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, ErrorCode code, String message) {
        if (status.is5xxServerError()) log.warn("{}: {}", code, message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ApiError(code, message));
    }
}
