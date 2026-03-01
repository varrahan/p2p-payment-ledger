package com.p2p.payment.service.impl;

import com.p2p.payment.domain.entity.*;
import com.p2p.payment.domain.enums.IdempotencyStatus;
import com.p2p.payment.domain.enums.LedgerEntryType;
import com.p2p.payment.domain.enums.TransferStatus;
import com.p2p.payment.dto.request.TransferRequest;
import com.p2p.payment.dto.response.ReconciliationResponse;
import com.p2p.payment.dto.response.TransferResponse;
import com.p2p.payment.exception.*;
import com.p2p.payment.repository.*;
import com.p2p.payment.notification.service.NotificationPublisher;
import com.p2p.payment.service.RateLimitService;
import com.p2p.payment.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferServiceImpl implements TransferService {

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final RateLimitService rateLimitService;
    private final NotificationPublisher notificationPublisher;

    @Value("${app.idempotency.ttl-hours}")
    private int idempotencyTtlHours;

    @Override
    @Transactional
    public TransferResponse transfer(UUID authenticatedUserId, String idempotencyKey, TransferRequest request) {
        // --- Step 1: Rate limit check (before any DB work) ---
        rateLimitService.checkTransferRateLimit(authenticatedUserId);

        // --- Step 2: Idempotency check ---
        var existingKey = idempotencyKeyRepository.findById(idempotencyKey);
        if (existingKey.isPresent()) {
            var ik = existingKey.get();
            if (ik.getStatus() == IdempotencyStatus.COMPLETED) {
                log.debug("Duplicate transfer request detected for key={}", idempotencyKey);
                return transferRepository.findByIdempotencyKey(idempotencyKey)
                        .map(this::toResponse)
                        .orElseThrow(() -> new ResourceNotFoundException("Transfer not found for key"));
            }
            if (ik.getStatus() == IdempotencyStatus.PROCESSING) {
                throw new IdempotencyConflictException(
                        "A request with this Idempotency-Key is already being processed");
            }
        }

        // --- Step 3: Validate sender ≠ receiver ---
        if (request.getSenderWalletId().equals(request.getReceiverWalletId())) {
            throw new TransferException("Sender and receiver wallets must be different");
        }

        // --- Step 4: Mark idempotency key as PROCESSING ---
        var ik = IdempotencyKey.builder()
                .key(idempotencyKey)
                .status(IdempotencyStatus.PROCESSING)
                .expiresAt(OffsetDateTime.now().plusHours(idempotencyTtlHours))
                .build();
        idempotencyKeyRepository.save(ik);

        // --- Step 5: Acquire pessimistic locks in consistent ID order (prevents deadlock) ---
        List<Wallet> lockedWallets = walletRepository.findByIdsWithLock(
                request.getSenderWalletId(), request.getReceiverWalletId());

        if (lockedWallets.size() != 2) {
            throw new ResourceNotFoundException("One or both wallets not found");
        }

        // Identify sender and receiver from the sorted list
        Wallet senderWallet = lockedWallets.stream()
                .filter(w -> w.getId().equals(request.getSenderWalletId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Sender wallet not found"));

        Wallet receiverWallet = lockedWallets.stream()
                .filter(w -> w.getId().equals(request.getReceiverWalletId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Receiver wallet not found"));

        // --- Step 6: Authorization — authenticated user must own the sender wallet ---
        if (!senderWallet.getUser().getId().equals(authenticatedUserId)) {
            throw new TransferException("You do not own the sender wallet");
        }

        // --- Step 7: Currency validation ---
        if (!senderWallet.getCurrency().equals(request.getCurrency())) {
            throw new TransferException(
                    "Currency mismatch: sender wallet is " + senderWallet.getCurrency());
        }
        if (!receiverWallet.getCurrency().equals(request.getCurrency())) {
            throw new TransferException(
                    "Currency mismatch: receiver wallet is " + receiverWallet.getCurrency());
        }

        // --- Step 8: Sufficient funds check ---
        if (senderWallet.getCurrentBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient funds. Available: " + senderWallet.getCurrentBalance()
                    + " " + senderWallet.getCurrency());
        }

        // --- Step 9: Create transfer record ---
        var transfer = Transfer.builder()
                .senderWallet(senderWallet)
                .receiverWallet(receiverWallet)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(TransferStatus.PROCESSING)
                .idempotencyKey(idempotencyKey)
                .description(request.getDescription())
                .build();
        transferRepository.save(transfer);

        // --- Step 10: Write double-entry ledger records ---
        var debitEntry = LedgerEntry.builder()
                .transfer(transfer)
                .wallet(senderWallet)
                .entryType(LedgerEntryType.DEBIT)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .build();
        ledgerEntryRepository.save(debitEntry);

        var creditEntry = LedgerEntry.builder()
                .transfer(transfer)
                .wallet(receiverWallet)
                .entryType(LedgerEntryType.CREDIT)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .build();
        ledgerEntryRepository.save(creditEntry);

        // --- Step 11: Update materialized balances (SAME transaction as ledger inserts) ---
        senderWallet.setCurrentBalance(senderWallet.getCurrentBalance().subtract(request.getAmount()));
        receiverWallet.setCurrentBalance(receiverWallet.getCurrentBalance().add(request.getAmount()));
        walletRepository.save(senderWallet);
        walletRepository.save(receiverWallet);

        // --- Step 12: Mark transfer COMPLETED ---
        transfer.setStatus(TransferStatus.COMPLETED);
        transferRepository.save(transfer);

        // --- Step 13: Write outbox event (Transactional Outbox pattern) ---
        writeOutboxEvent("TransferCompletedEvent", transfer);

        // --- Step 14: Publish notification events ---
        // Notify receiver — Push only (fast UX confirmation)
        var receiver = receiverWallet.getUser();
        var sender   = senderWallet.getUser();
        notificationPublisher.publishTransferReceived(
                receiver.getId(),
                receiver.getEmail(),
                receiver.getFullName(),
                request.getAmount(),
                request.getCurrency(),
                sender.getFullName(),
                transfer.getId()
        );

        // Notify sender if this is a large withdrawal — Push + Email (security trail)
        notificationPublisher.publishLargeWithdrawalIfRequired(
                sender.getId(),
                sender.getEmail(),
                sender.getFullName(),
                request.getAmount(),
                request.getCurrency(),
                senderWallet.getId()
        );

        // --- Step 15: Mark idempotency key COMPLETED ---
        ik.setStatus(IdempotencyStatus.COMPLETED);
        idempotencyKeyRepository.save(ik);

        // Transaction commits here — all steps are atomic
        log.info("Transfer completed id={} amount={} {}", transfer.getId(), request.getAmount(), request.getCurrency());
        return toResponse(transfer);
    }

    @Override
    @Transactional
    public TransferResponse reverse(UUID transferId, UUID authenticatedUserId, String idempotencyKey) {
        // Idempotency check for reversal
        var existingKey = idempotencyKeyRepository.findById(idempotencyKey);
        if (existingKey.isPresent() && existingKey.get().getStatus() == IdempotencyStatus.COMPLETED) {
            return transferRepository.findById(transferId)
                    .map(this::toResponse)
                    .orElseThrow(() -> new ResourceNotFoundException("Transfer not found"));
        }
        if (existingKey.isPresent() && existingKey.get().getStatus() == IdempotencyStatus.PROCESSING) {
            throw new IdempotencyConflictException("Reversal is already being processed");
        }

        var ik = IdempotencyKey.builder()
                .key(idempotencyKey)
                .status(IdempotencyStatus.PROCESSING)
                .expiresAt(OffsetDateTime.now().plusHours(idempotencyTtlHours))
                .build();
        idempotencyKeyRepository.save(ik);

        // Load and validate original transfer
        var transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found: " + transferId));

        if (transfer.getStatus() != TransferStatus.COMPLETED) {
            throw new TransferException("Only COMPLETED transfers can be reversed. Current status: " + transfer.getStatus());
        }

        // Authorization: original sender must initiate the reversal
        if (!transfer.getSenderWallet().getUser().getId().equals(authenticatedUserId)) {
            throw new TransferException("Only the original sender can reverse this transfer");
        }

        // Acquire locks in consistent ID order
        List<Wallet> lockedWallets = walletRepository.findByIdsWithLock(
                transfer.getSenderWallet().getId(), transfer.getReceiverWallet().getId());

        Wallet originalSender = lockedWallets.stream()
                .filter(w -> w.getId().equals(transfer.getSenderWallet().getId()))
                .findFirst().orElseThrow();

        Wallet originalReceiver = lockedWallets.stream()
                .filter(w -> w.getId().equals(transfer.getReceiverWallet().getId()))
                .findFirst().orElseThrow();

        // Check receiver still has sufficient funds
        if (originalReceiver.getCurrentBalance().compareTo(transfer.getAmount()) < 0) {
            throw new InsufficientFundsException("Receiver has insufficient funds to reverse this transfer");
        }

        // Offsetting double-entry: debit original receiver, credit original sender
        var reversalDebit = LedgerEntry.builder()
                .transfer(transfer)
                .wallet(originalReceiver)
                .entryType(LedgerEntryType.DEBIT)
                .amount(transfer.getAmount())
                .currency(transfer.getCurrency())
                .build();
        ledgerEntryRepository.save(reversalDebit);

        var reversalCredit = LedgerEntry.builder()
                .transfer(transfer)
                .wallet(originalSender)
                .entryType(LedgerEntryType.CREDIT)
                .amount(transfer.getAmount())
                .currency(transfer.getCurrency())
                .build();
        ledgerEntryRepository.save(reversalCredit);

        // Update balances
        originalReceiver.setCurrentBalance(originalReceiver.getCurrentBalance().subtract(transfer.getAmount()));
        originalSender.setCurrentBalance(originalSender.getCurrentBalance().add(transfer.getAmount()));
        walletRepository.save(originalReceiver);
        walletRepository.save(originalSender);

        // Transition transfer to REVERSED (original records are immutable, only status changes)
        transfer.setStatus(TransferStatus.REVERSED);
        transferRepository.save(transfer);

        writeOutboxEvent("TransferReversedEvent", transfer);

        ik.setStatus(IdempotencyStatus.COMPLETED);
        idempotencyKeyRepository.save(ik);

        log.info("Transfer reversed id={}", transferId);
        return toResponse(transfer);
    }

    @Override
    @Transactional(readOnly = true)
    public TransferResponse getById(UUID transferId, UUID authenticatedUserId) {
        var transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found: " + transferId));

        // Only sender or receiver can view the transfer
        UUID senderId = transfer.getSenderWallet().getUser().getId();
        UUID receiverId = transfer.getReceiverWallet().getUser().getId();
        if (!senderId.equals(authenticatedUserId) && !receiverId.equals(authenticatedUserId)) {
            throw new TransferException("Access denied to this transfer");
        }

        return toResponse(transfer);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransferResponse> getTransferHistory(UUID walletId, UUID authenticatedUserId, Pageable pageable) {
        var wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));

        if (!wallet.getUser().getId().equals(authenticatedUserId)) {
            throw new TransferException("You do not own this wallet");
        }

        return transferRepository.findByWalletId(walletId, pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile() {
        BigDecimal totalDebits  = ledgerEntryRepository.sumByEntryType(LedgerEntryType.DEBIT);
        BigDecimal totalCredits = ledgerEntryRepository.sumByEntryType(LedgerEntryType.CREDIT);
        BigDecimal discrepancy  = totalDebits.subtract(totalCredits);
        boolean balanced = discrepancy.compareTo(BigDecimal.ZERO) == 0;

        if (!balanced) {
            log.error("RECONCILIATION DISCREPANCY DETECTED: debits={} credits={} diff={}",
                    totalDebits, totalCredits, discrepancy);
        }

        return ReconciliationResponse.builder()
                .totalDebits(totalDebits)
                .totalCredits(totalCredits)
                .discrepancy(discrepancy)
                .balanced(balanced)
                .checkedAt(OffsetDateTime.now())
                .build();
    }

    private void writeOutboxEvent(String eventType, Transfer transfer) {
        String payload = String.format(
                "{\"transferId\":\"%s\",\"senderWalletId\":\"%s\",\"receiverWalletId\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}",
                transfer.getId(), transfer.getSenderWallet().getId(),
                transfer.getReceiverWallet().getId(), transfer.getAmount(), transfer.getCurrency());

        var event = OutboxEvent.builder()
                .eventType(eventType)
                .payload(payload)
                .build();
        outboxEventRepository.save(event);
    }

    public TransferResponse toResponse(Transfer transfer) {
        return TransferResponse.builder()
                .id(transfer.getId())
                .senderWalletId(transfer.getSenderWallet().getId())
                .receiverWalletId(transfer.getReceiverWallet().getId())
                .amount(transfer.getAmount())
                .currency(transfer.getCurrency())
                .status(transfer.getStatus())
                .description(transfer.getDescription())
                .createdAt(transfer.getCreatedAt())
                .updatedAt(transfer.getUpdatedAt())
                .build();
    }
}