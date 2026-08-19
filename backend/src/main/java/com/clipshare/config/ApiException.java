package com.clipshare.config;

import org.springframework.http.HttpStatus;

/**
 * Excepción de negocio con un código de error estable (para que el frontend lo pueda
 * mapear sin parsear el mensaje) y el HTTP status correspondiente. Ver formato de error
 * consistente exigido en docs/SPEC.md sección 8.
 */
public class ApiException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static ApiException badRequest(String errorCode, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, errorCode, message);
    }

    public static ApiException unauthorized(String errorCode, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, errorCode, message);
    }

    public static ApiException forbidden(String errorCode, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, errorCode, message);
    }

    public static ApiException conflict(String errorCode, String message) {
        return new ApiException(HttpStatus.CONFLICT, errorCode, message);
    }

    public static ApiException notFound(String errorCode, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, errorCode, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
