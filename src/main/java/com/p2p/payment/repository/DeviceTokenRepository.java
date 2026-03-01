package com.p2p.payment.repository;

import com.p2p.payment.domain.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    @Query("SELECT dt FROM DeviceToken dt WHERE dt.user.id = :userId AND dt.active = true")
    List<DeviceToken> findActiveTokensByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE DeviceToken dt SET dt.active = false WHERE dt.token = :token")
    void deactivateToken(@Param("token") String token);

    boolean existsByToken(String token);
}