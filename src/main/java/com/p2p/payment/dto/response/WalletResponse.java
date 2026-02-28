package com.p2p.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WalletResponse {
    private UUID id;
    private UUID userId;
    private String currency;
    private BigDecimal currentBalance;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
