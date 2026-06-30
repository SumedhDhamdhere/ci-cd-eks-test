package com.ecommerce.order.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        HttpStatus status;
        if (message.contains("not found")) status = HttpStatus.NOT_FOUND;
        else if (message.contains("Cannot cancel")) status = HttpStatus.CONFLICT;
        else status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
