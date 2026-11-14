package ie.ucd.space.firecal.api.web;

import ie.ucd.space.firecal.contract.api.ErrorCode;
import org.springframework.http.HttpStatus;

/** Thrown anywhere in the API; rendered as HTTP status + {"code","message"}. */
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

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.BAD_INPUT, HttpStatus.NOT_FOUND, message);
    }

    public static ApiException outsideNa(String message) {
        return new ApiException(ErrorCode.AOI_OUTSIDE_NA, HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException tooLarge(String message) {
        return new ApiException(ErrorCode.AOI_TOO_LARGE, HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException upstream(String message) {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public ErrorCode code() { return code; }
    public HttpStatus status() { return status; }
}
