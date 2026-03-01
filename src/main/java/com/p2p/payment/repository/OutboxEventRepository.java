package com.p2p.payment.repository;

import com.p2p.payment.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.published = true WHERE e.id = :id")
    void markAsPublished(@Param("id") UUID id);
}
