package ie.ucd.spaceteam.firecal.api.web;

import ie.ucd.spaceteam.firecal.contract.api.ApiError;
import ie.ucd.spaceteam.firecal.contract.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e) {
        return error(e.status(), e.code(), e.getMessage());
    }

    /** Unparseable JSON, wrong types, bad "YYYY-MM", unknown aoi type, unknown fields. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        return error(HttpStatus.BAD_REQUEST, ErrorCode.BAD_INPUT, "invalid request body: " + firstLine(cause.getMessage()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> badParam(Exception e) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.BAD_INPUT, firstLine(e.getMessage()));
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, ErrorCode code, String message) {
        log.debug("{} {}: {}", status.value(), code, message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ApiError(code, message));
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
