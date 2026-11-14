package ie.ucd.spaceteam.firecal.api.web;

import ie.ucd.spaceteam.firecal.contract.api.ErrorCode;
import org.springframework.http.HttpStatus;

/** Thrown anywhere in the api; rendered as HTTP status + {"code", "message"}. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;

    public ApiException(ErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static ApiException badInput(String message) {
        return new ApiException(ErrorCode.BAD_INPUT, HttpStatus.BAD_REQUEST, message);
    }

    public ErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
