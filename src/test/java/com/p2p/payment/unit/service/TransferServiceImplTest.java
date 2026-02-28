package com.p2p.payment.unit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2p.payment.domain.entity.*;
import com.p2p.payment.domain.enums.IdempotencyStatus;
import com.p2p.payment.dto.request.TransferRequest;
import com.p2p.payment.exception.IdempotencyConflictException;
import com.p2p.payment.exception.InsufficientFundsException;
import com.p2p.payment.exception.TransferException;
import com.p2p.payment.repository.*;
import com.p2p.payment.service.RateLimitService;
import com.p2p.payment.service.impl.TransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService Unit Tests")
class TransferServiceImplTest {

    @Mock private TransferRepository transferRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private RateLimitService rateLimitService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private TransferServiceImpl transferService;

    private User alice;
    private User bob;
    private Wallet aliceWallet;
    private Wallet bobWallet;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(java.util.Objects.requireNonNull(transferService, "TransferService must not be null"), "idempotencyTtlHours", 24);

        alice = User.builder().id(UUID.randomUUID()).email("alice@test.com").password("hashed").fullName("Alice").build();
        bob   = User.builder().id(UUID.randomUUID()).email("bob@test.com").password("hashed").fullName("Bob").build();

        aliceWallet = Wallet.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .user(alice).currency("USD")
                .currentBalance(new BigDecimal("500.00"))
                .build();

        bobWallet = Wallet.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .user(bob).currency("USD")
                .currentBalance(BigDecimal.ZERO)
                .build();
    }

    @Test
    @SuppressWarnings("null")
    @DisplayName("Throws TransferException when sender and receiver wallets are identical")
    void transfer_sameWallet_throwsException() {
        var request = buildRequest(aliceWallet.getId(), aliceWallet.getId(), "10.00");
        when(idempotencyKeyRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(alice.getId(), "key-1", request))
                .isInstanceOf(TransferException.class)
                .hasMessageContaining("different");
    }

    @Test
    @SuppressWarnings("null")
    @DisplayName("Throws InsufficientFundsException when balance is too low")
    void transfer_insufficientFunds_throwsException() {
        var request = buildRequest(aliceWallet.getId(), bobWallet.getId(), "9999.00");

        when(idempotencyKeyRepository.findById(any())).thenReturn(Optional.empty());
        when(idempotencyKeyRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(walletRepository.findByIdsWithLock(any(), any())).thenReturn(List.of(aliceWallet, bobWallet));

        assertThatThrownBy(() -> transferService.transfer(alice.getId(), "key-2", request))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    @SuppressWarnings("null")
    @DisplayName("Throws TransferException when authenticated user does not own sender wallet")
    void transfer_unauthorizedSender_throwsException() {
        UUID impostor = UUID.randomUUID();
        var request = buildRequest(aliceWallet.getId(), bobWallet.getId(), "10.00");

        when(idempotencyKeyRepository.findById(any())).thenReturn(Optional.empty());
        when(idempotencyKeyRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(walletRepository.findByIdsWithLock(any(), any())).thenReturn(List.of(aliceWallet, bobWallet));

        assertThatThrownBy(() -> transferService.transfer(impostor, "key-3", request))
                .isInstanceOf(TransferException.class)
                .hasMessageContaining("do not own");
    }

    @Test
    @DisplayName("Throws IdempotencyConflictException when key is PROCESSING")
    void transfer_processingIdempotencyKey_throwsConflict() {
        var processingKey = IdempotencyKey.builder()
                .key("dup-key")
                .status(IdempotencyStatus.PROCESSING)
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .build();

        when(idempotencyKeyRepository.findById("dup-key")).thenReturn(Optional.of(processingKey));

        var request = buildRequest(aliceWallet.getId(), bobWallet.getId(), "10.00");

        assertThatThrownBy(() -> transferService.transfer(alice.getId(), "dup-key", request))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @SuppressWarnings("null")
    @DisplayName("Throws TransferException when currency mismatches sender wallet")
    void transfer_currencyMismatch_throwsException() {
        aliceWallet = Wallet.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .user(alice).currency("EUR")
                .currentBalance(new BigDecimal("500.00"))
                .build();

        var request = buildRequest(aliceWallet.getId(), bobWallet.getId(), "10.00");
        request.setCurrency("USD");

        when(idempotencyKeyRepository.findById(any())).thenReturn(Optional.empty());
        when(idempotencyKeyRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(walletRepository.findByIdsWithLock(any(), any())).thenReturn(List.of(aliceWallet, bobWallet));

        assertThatThrownBy(() -> transferService.transfer(alice.getId(), "key-4", request))
                .isInstanceOf(TransferException.class)
                .hasMessageContaining("Currency mismatch");
    }

    private TransferRequest buildRequest(UUID senderId, UUID receiverId, String amount) {
        var req = new TransferRequest();
        req.setSenderWalletId(senderId);
        req.setReceiverWalletId(receiverId);
        req.setAmount(new BigDecimal(amount));
        req.setCurrency("USD");
        return req;
    }
}
