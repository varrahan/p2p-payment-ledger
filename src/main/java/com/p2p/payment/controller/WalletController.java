package com.p2p.payment.controller;

import com.p2p.payment.dto.request.DepositRequest;
import com.p2p.payment.dto.response.ApiResponse;
import com.p2p.payment.dto.response.WalletResponse;
import com.p2p.payment.security.SecurityUtils;
import com.p2p.payment.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(
            @RequestParam(defaultValue = "USD") String currency) {
        UUID userId = securityUtils.getAuthenticatedUserId();
        var wallet = walletService.createWallet(userId, currency.toUpperCase());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Wallet created", wallet));
    }

    @GetMapping("/{walletId}")
    public ResponseEntity<ApiResponse<WalletResponse>> getWallet(@PathVariable UUID walletId) {
        UUID userId = securityUtils.getAuthenticatedUserId();
        var wallet = walletService.getWallet(walletId, userId);
        return ResponseEntity.ok(ApiResponse.ok(wallet));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<WalletResponse>>> getMyWallets() {
        UUID userId = securityUtils.getAuthenticatedUserId();
        var wallets = walletService.getWalletsForUser(userId);
        return ResponseEntity.ok(ApiResponse.ok(wallets));
    }

    @PostMapping("/{walletId}/deposit")
    public ResponseEntity<ApiResponse<WalletResponse>> deposit(
            @PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        UUID userId = securityUtils.getAuthenticatedUserId();
        var wallet = walletService.deposit(walletId, userId, idempotencyKey, request);
        return ResponseEntity.ok(ApiResponse.ok("Deposit successful", wallet));
    }
}
