package com.p2p.payment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class TransferException extends RuntimeException {
    public TransferException(String message) {
        super(message);
    }
}
