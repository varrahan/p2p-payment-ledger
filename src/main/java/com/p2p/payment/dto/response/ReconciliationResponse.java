package com.p2p.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReconciliationResponse {
    private BigDecimal totalDebits;
    private BigDecimal totalCredits;
    private BigDecimal discrepancy;
    private boolean balanced;
    private OffsetDateTime checkedAt;
}
