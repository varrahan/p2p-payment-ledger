package com.p2p.payment.service;

import com.p2p.payment.dto.request.DepositRequest;
import com.p2p.payment.dto.response.WalletResponse;

import java.util.List;
import java.util.UUID;

public interface WalletService {

    WalletResponse createWallet(UUID userId, String currency);

    WalletResponse deposit(UUID walletId, UUID authenticatedUserId, String idempotencyKey, DepositRequest request);

    WalletResponse getWallet(UUID walletId, UUID authenticatedUserId);

    List<WalletResponse> getWalletsForUser(UUID userId);
}
