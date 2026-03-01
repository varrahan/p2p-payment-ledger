package com.p2p.payment.service.impl;

import com.p2p.payment.domain.entity.*;
import com.p2p.payment.domain.enums.IdempotencyStatus;
import com.p2p.payment.domain.enums.LedgerEntryType;
import com.p2p.payment.domain.enums.TransferStatus;
import com.p2p.payment.dto.request.DepositRequest;
import com.p2p.payment.dto.response.WalletResponse;
import com.p2p.payment.exception.*;
import com.p2p.payment.repository.*;
import com.p2p.payment.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransferRepository transferRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEventRepository outboxEventRepository;

    @Value("${app.idempotency.ttl-hours}")
    private int idempotencyTtlHours;

    @Override
    @Transactional
    public WalletResponse createWallet(UUID userId, String currency) {
        var user = userRepository.findById(java.util.Objects.requireNonNull(userId, "User ID cannot be null"))
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        var wallet = Wallet.builder()
                .user(user)
                .currency(currency)
                .build();

        walletRepository.save(java.util.Objects.requireNonNull(wallet, "Wallet cannot be null"));
        log.info("Created wallet id={} for user id={}", wallet.getId(), userId);
        return toResponse(wallet);
    }

    @Override
    @Transactional
    public WalletResponse deposit(UUID walletId, UUID authenticatedUserId,
                                   String idempotencyKey, DepositRequest request) {
        // --- Idempotency check ---
        var existingKey = idempotencyKeyRepository.findById(java.util.Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null"));
        if (existingKey.isPresent()) {
            var ik = existingKey.get();
            if (ik.getStatus() == IdempotencyStatus.COMPLETED) {
                // Return current wallet state — deposit already applied
                var wallet = walletRepository.findById(java.util.Objects.requireNonNull(walletId, "Wallet ID cannot be null"))
                        .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
                return toResponse(wallet);
            }
            if (ik.getStatus() == IdempotencyStatus.PROCESSING) {
                throw new IdempotencyConflictException("Request with this Idempotency-Key is already being processed");
            }
        }

        // Mark as PROCESSING
        var ik = IdempotencyKey.builder()
                .key(idempotencyKey)
                .status(IdempotencyStatus.PROCESSING)
                .expiresAt(OffsetDateTime.now().plusHours(idempotencyTtlHours))
                .build();
        idempotencyKeyRepository.save(java.util.Objects.requireNonNull(ik, "Idempotency key cannot be null"));

        // Acquire pessimistic lock on wallet
        var wallet = walletRepository.findByIdWithLock(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));

        // Authorization: only the wallet owner can deposit
        if (!wallet.getUser().getId().equals(authenticatedUserId)) {
            throw new TransferException("You do not own this wallet");
        }

        // Currency must match
        if (!wallet.getCurrency().equals(request.getCurrency())) {
            throw new TransferException(
                    "Currency mismatch: wallet is " + wallet.getCurrency() + ", deposit is " + request.getCurrency());
        }

        // Create a synthetic "DEPOSIT" transfer record
        var depositTransfer = Transfer.builder()
                .senderWallet(wallet) // Same wallet for deposit (external funding)
                .receiverWallet(wallet)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(TransferStatus.COMPLETED)
                .idempotencyKey(idempotencyKey)
                .description("External deposit")
                .build();
        transferRepository.save(java.util.Objects.requireNonNull(depositTransfer, "Deposit transfer cannot be null"));

        // Credit ledger entry
        var creditEntry = LedgerEntry.builder()
                .transfer(depositTransfer)
                .wallet(wallet)
                .entryType(LedgerEntryType.CREDIT)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .build();
        ledgerEntryRepository.save(java.util.Objects.requireNonNull(creditEntry, "Credit entry cannot be null"));

        // Update materialized balance — SAME transaction
        wallet.setCurrentBalance(wallet.getCurrentBalance().add(request.getAmount()));
        walletRepository.save(wallet);

        // Outbox event
        var outboxEvent = OutboxEvent.builder()
                .eventType("DepositCompletedEvent")
                .payload("{\"walletId\":\"" + walletId + "\",\"amount\":\"" + request.getAmount() + "\"}")
                .build();
        outboxEventRepository.save(java.util.Objects.requireNonNull(outboxEvent, "Outbox event cannot be null"));

        // Mark idempotency key COMPLETED
        ik.setStatus(IdempotencyStatus.COMPLETED);
        idempotencyKeyRepository.save(ik);

        log.info("Deposit completed walletId={} amount={} currency={}", walletId, request.getAmount(), request.getCurrency());
        return toResponse(wallet);
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID walletId, UUID authenticatedUserId) {
        var wallet = walletRepository.findById(java.util.Objects.requireNonNull(walletId, "Wallet ID cannot be null"))
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));

        if (!wallet.getUser().getId().equals(authenticatedUserId)) {
            throw new TransferException("You do not own this wallet");
        }

        return toResponse(wallet);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletResponse> getWalletsForUser(UUID userId) {
        return walletRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public WalletResponse toResponse(Wallet wallet) {
        return WalletResponse.builder()
                .id(wallet.getId())
                .userId(wallet.getUser().getId())
                .currency(wallet.getCurrency())
                .currentBalance(wallet.getCurrentBalance())
                .createdAt(wallet.getCreatedAt())
                .updatedAt(wallet.getUpdatedAt())
                .build();
    }
}
