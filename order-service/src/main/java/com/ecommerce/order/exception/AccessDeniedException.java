package com.ecommerce.order.exception;

/** The caller is authenticated but is asking for someone else's data. */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) { super(message); }
}
