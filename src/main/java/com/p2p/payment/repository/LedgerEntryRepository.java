package com.p2p.payment.repository;

import com.p2p.payment.domain.entity.LedgerEntry;
import com.p2p.payment.domain.enums.LedgerEntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransferId(UUID transferId);

    List<LedgerEntry> findByWalletId(UUID walletId);

    /**
     * Reconciliation query: sum of DEBITs should equal sum of CREDITs in a closed system.
     */
    @Query("""
            SELECT COALESCE(SUM(e.amount), 0)
            FROM LedgerEntry e
            WHERE e.entryType = :entryType
            """)
    BigDecimal sumByEntryType(@Param("entryType") LedgerEntryType entryType);

    /**
     * Derive balance from ledger (source of truth).
     * Used to detect discrepancies with materialized current_balance.
     */
    @Query("""
            SELECT COALESCE(
              SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE 0 END) -
              SUM(CASE WHEN e.entryType = 'DEBIT'  THEN e.amount ELSE 0 END),
              0)
            FROM LedgerEntry e
            WHERE e.wallet.id = :walletId
            """)
    BigDecimal deriveBalanceForWallet(@Param("walletId") UUID walletId);
}
