package com.ecommerce.order.exception;

/**
 * Also thrown when the order exists but belongs to somebody else. Answering 403
 * there would confirm that the id is real, which is all an attacker needs to
 * enumerate the order table one id at a time.
 */
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(String message) { super(message); }
}
