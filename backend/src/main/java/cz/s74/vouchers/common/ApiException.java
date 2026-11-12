package cz.s74.vouchers.common;

import org.springframework.http.HttpStatus;

/** Chyba veřejného API: {@code { "error": CODE, "message": česky }}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
