package com.p2p.payment.repository;

import com.p2p.payment.domain.entity.Transfer;
import com.p2p.payment.domain.enums.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            SELECT t FROM Transfer t
            WHERE t.senderWallet.id = :walletId
               OR t.receiverWallet.id = :walletId
            ORDER BY t.createdAt DESC
            """)
    Page<Transfer> findByWalletId(@Param("walletId") UUID walletId, Pageable pageable);

    List<Transfer> findByStatus(TransferStatus status);
}
