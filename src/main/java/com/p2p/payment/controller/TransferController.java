package com.p2p.payment.controller;

import com.p2p.payment.dto.request.TransferRequest;
import com.p2p.payment.dto.response.ApiResponse;
import com.p2p.payment.dto.response.ReconciliationResponse;
import com.p2p.payment.dto.response.TransferResponse;
import com.p2p.payment.security.SecurityUtils;
import com.p2p.payment.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;
    private final SecurityUtils securityUtils;

    /**
     * Core P2P transfer endpoint.
     * Requires Idempotency-Key header to guarantee exactly-once processing.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TransferResponse>> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        UUID userId = securityUtils.getAuthenticatedUserId();
        var transfer = transferService.transfer(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Transfer completed", transfer));
    }

    @PostMapping("/{transferId}/reverse")
    public ResponseEntity<ApiResponse<TransferResponse>> reverse(
            @PathVariable UUID transferId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        UUID userId = securityUtils.getAuthenticatedUserId();
        var transfer = transferService.reverse(transferId, userId, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.ok("Transfer reversed", transfer));
    }

    @GetMapping("/{transferId}")
    public ResponseEntity<ApiResponse<TransferResponse>> getTransfer(@PathVariable UUID transferId) {
        UUID userId = securityUtils.getAuthenticatedUserId();
        var transfer = transferService.getById(transferId, userId);
        return ResponseEntity.ok(ApiResponse.ok(transfer));
    }

    @GetMapping("/wallet/{walletId}")
    public ResponseEntity<ApiResponse<Page<TransferResponse>>> getHistory(
            @PathVariable UUID walletId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        UUID userId = securityUtils.getAuthenticatedUserId();
        var history = transferService.getTransferHistory(walletId, userId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(history));
    }

    /**
     * Reconciliation endpoint — verifies SUM(debits) == SUM(credits).
     * In production this would be restricted to an ADMIN role.
     */
    @GetMapping("/reconcile")
    public ResponseEntity<ApiResponse<ReconciliationResponse>> reconcile() {
        var result = transferService.reconcile();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
