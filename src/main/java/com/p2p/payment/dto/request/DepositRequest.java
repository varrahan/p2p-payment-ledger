package com.p2p.payment.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DepositRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.0001", message = "Amount must be greater than 0")
    @Digits(integer = 15, fraction = 4, message = "Amount format invalid")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be a 3-character ISO 4217 code")
    @Pattern(regexp = "[A-Z]{3}", message = "Currency must be uppercase letters (e.g. USD)")
    private String currency;
}
