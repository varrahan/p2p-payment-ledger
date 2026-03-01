package com.p2p.payment.service;

import com.p2p.payment.dto.request.TransferRequest;
import com.p2p.payment.dto.response.ReconciliationResponse;
import com.p2p.payment.dto.response.TransferResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TransferService {

    TransferResponse transfer(UUID authenticatedUserId, String idempotencyKey, TransferRequest request);

    TransferResponse reverse(UUID transferId, UUID authenticatedUserId, String idempotencyKey);

    TransferResponse getById(UUID transferId, UUID authenticatedUserId);

    Page<TransferResponse> getTransferHistory(UUID walletId, UUID authenticatedUserId, Pageable pageable);

    ReconciliationResponse reconcile();
}
