package com.p2p.payment.integration;

import com.p2p.payment.domain.entity.User;
import com.p2p.payment.domain.entity.Wallet;
import com.p2p.payment.domain.enums.LedgerEntryType;
import com.p2p.payment.domain.enums.TransferStatus;
import com.p2p.payment.dto.request.TransferRequest;
import com.p2p.payment.repository.*;
import com.p2p.payment.security.JwtService;
import com.p2p.payment.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Transfer Integration Tests")
class TransferIntegrationTest extends BaseIntegrationTest {

    @Autowired private TransferService transferService;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private User alice;
    private User bob;
    private Wallet aliceWallet;
    private Wallet bobWallet;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(User.builder()
                .email("alice@example.com")
                .password(passwordEncoder.encode("password"))
                .fullName("Alice Test")
                .build());

        bob = userRepository.save(User.builder()
                .email("bob@example.com")
                .password(passwordEncoder.encode("password"))
                .fullName("Bob Test")
                .build());

        aliceWallet = walletRepository.save(Wallet.builder()
                .user(alice)
                .currency("USD")
                .currentBalance(new BigDecimal("1000.00"))
                .build());

        bobWallet = walletRepository.save(Wallet.builder()
                .user(bob)
                .currency("USD")
                .currentBalance(new BigDecimal("500.00"))
                .build());
    }

    @Test
    @DisplayName("Happy path: transfer creates correct ledger entries and updates balances atomically")
    void transfer_happyPath_createsDoubleEntryAndUpdatesBalances() {
        var request = buildRequest(aliceWallet.getId(), bobWallet.getId(), "100.00");

        var response = transferService.transfer(alice.getId(), UUID.randomUUID().toString(), request);

        assertThat(response.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(response.getAmount()).isEqualByComparingTo("100.00");

        // Verify materialized balances
        var updatedAlice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        var updatedBob   = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertThat(updatedAlice.getCurrentBalance()).isEqualByComparingTo("900.00");
        assertThat(updatedBob.getCurrentBalance()).isEqualByComparingTo("600.00");

        // Verify double-entry ledger
        var entries = ledgerEntryRepository.findByTransferId(response.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).anyMatch(e -> e.getEntryType() == LedgerEntryType.DEBIT
                && e.getWallet().getId().equals(aliceWallet.getId())
                && e.getAmount().compareTo(new BigDecimal("100.00")) == 0);
        assertThat(entries).anyMatch(e -> e.getEntryType() == LedgerEntryType.CREDIT
                && e.getWallet().getId().equals(bobWallet.getId())
                && e.getAmount().compareTo(new BigDecimal("100.00")) == 0);
    }

    @Test
    @DisplayName("Idempotency: duplicate request with same key returns same result without double-processing")
    void transfer_duplicateIdempotencyKey_returnsSameResult() {
        String idempotencyKey = UUID.randomUUID().toString();
        var request = buildRequest(aliceWallet.getId(), bobWallet.getId(), "50.00");

        var first  = transferService.transfer(alice.getId(), idempotencyKey, request);
        var second = transferService.transfer(alice.getId(), idempotencyKey, request);

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(first.getStatus()).isEqualTo(second.getStatus());

        // Balance should only be affected once
        var updatedAlice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        assertThat(updatedAlice.getCurrentBalance()).isEqualByComparingTo("950.00");

        // Only 2 ledger entries (1 debit + 1 credit) — not 4
        long entryCount = ledgerEntryRepository.findByTransferId(first.getId()).size();
        assertThat(entryCount).isEqualTo(2);
    }

    @Test
    @DisplayName("Insufficient funds: transfer fails with informative error")
    void transfer_insufficientFunds_throwsException() {
        var request = buildRequest(aliceWallet.getId(), bobWallet.getId(), "9999.00");

        assertThatThrownBy(() ->
            transferService.transfer(alice.getId(), UUID.randomUUID().toString(), request))
            .hasMessageContaining("Insufficient funds");

        // Balances must not change
        var updatedAlice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        assertThat(updatedAlice.getCurrentBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Concurrency: N simultaneous transfers cannot overdraw the sender wallet")
    void transfer_concurrentRequests_cannotOverdraw() throws InterruptedException {
        // Alice has $1000, we fire 20 concurrent transfers of $100 each
        // Only 10 should succeed; balance must never go negative
        int threadCount = 20;
        BigDecimal transferAmount = new BigDecimal("100.00");
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final String key = UUID.randomUUID().toString();
            pool.submit(() -> {
                try {
                    startLatch.await();
                    var req = buildRequest(aliceWallet.getId(), bobWallet.getId(),
                            transferAmount.toPlainString());
                    transferService.transfer(alice.getId(), key, req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Fire all threads simultaneously
        doneLatch.await();
        pool.shutdown();

        // Exactly 10 should succeed
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failureCount.get()).isEqualTo(10);

        // Final balance must be exactly $0
        var updatedAlice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        assertThat(updatedAlice.getCurrentBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Reconciliation: ledger debits always equal credits in a closed system")
    void reconcile_afterMultipleTransfers_debitEqualsCredit() {
        // Multiple transfers
        transferService.transfer(alice.getId(), UUID.randomUUID().toString(),
                buildRequest(aliceWallet.getId(), bobWallet.getId(), "100.00"));
        transferService.transfer(alice.getId(), UUID.randomUUID().toString(),
                buildRequest(aliceWallet.getId(), bobWallet.getId(), "50.00"));
        transferService.transfer(bob.getId(), UUID.randomUUID().toString(),
                buildRequest(bobWallet.getId(), aliceWallet.getId(), "30.00"));

        var result = transferService.reconcile();

        assertThat(result.isBalanced()).isTrue();
        assertThat(result.getDiscrepancy()).isEqualByComparingTo("0.00");
        assertThat(result.getTotalDebits()).isEqualByComparingTo(result.getTotalCredits());
    }

    @Test
    @DisplayName("Reversal: creates offsetting ledger entries and restores balances")
    void reverse_completedTransfer_restoresBalances() {
        String idempotencyKey = UUID.randomUUID().toString();
        var transfer = transferService.transfer(alice.getId(), idempotencyKey,
                buildRequest(aliceWallet.getId(), bobWallet.getId(), "200.00"));

        assertThat(walletRepository.findById(aliceWallet.getId()).orElseThrow()
                .getCurrentBalance()).isEqualByComparingTo("800.00");

        // Reverse it
        transferService.reverse(transfer.getId(), alice.getId(), UUID.randomUUID().toString());

        // Balances restored
        assertThat(walletRepository.findById(aliceWallet.getId()).orElseThrow()
                .getCurrentBalance()).isEqualByComparingTo("1000.00");
        assertThat(walletRepository.findById(bobWallet.getId()).orElseThrow()
                .getCurrentBalance()).isEqualByComparingTo("500.00");

        // Transfer status is REVERSED
        var reversed = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(reversed.getStatus()).isEqualTo(TransferStatus.REVERSED);

        // 4 ledger entries: original debit+credit + reversal debit+credit
        assertThat(ledgerEntryRepository.findByTransferId(transfer.getId())).hasSize(4);
    }

    private TransferRequest buildRequest(UUID senderWalletId, UUID receiverWalletId, String amount) {
        var req = new TransferRequest();
        req.setSenderWalletId(senderWalletId);
        req.setReceiverWalletId(receiverWalletId);
        req.setAmount(new BigDecimal(amount));
        req.setCurrency("USD");
        req.setDescription("Test transfer");
        return req;
    }
}
