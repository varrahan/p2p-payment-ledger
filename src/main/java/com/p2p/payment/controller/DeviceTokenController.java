package com.p2p.payment.controller;

import com.p2p.payment.domain.entity.DeviceToken;
import com.p2p.payment.dto.response.ApiResponse;
import com.p2p.payment.repository.DeviceTokenRepository;
import com.p2p.payment.repository.UserRepository;
import com.p2p.payment.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;

    /**
     * Called by the mobile/web app after the user grants push permission
     * and receives an FCM token from Firebase.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> registerToken(
            @Valid @RequestBody RegisterTokenRequest request) {

        UUID userId = securityUtils.getAuthenticatedUserId();

        // Avoid duplicates — token may already be registered for this user
        if (!deviceTokenRepository.existsByToken(request.getToken())) {
            var user = userRepository.findById(userId).orElseThrow();
            var deviceToken = DeviceToken.builder()
                    .user(user)
                    .token(request.getToken())
                    .deviceType(request.getDeviceType())
                    .build();
            deviceTokenRepository.save(deviceToken);
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Device registered for push notifications", null));
    }

    /**
     * Called when a user logs out or revokes push permission.
     */
    @DeleteMapping("/deregister")
    public ResponseEntity<ApiResponse<Void>> deregisterToken(
            @Valid @RequestBody DeregisterTokenRequest request) {

        deviceTokenRepository.deactivateToken(request.getToken());
        return ResponseEntity.ok(ApiResponse.ok("Device deregistered", null));
    }

    @Data
    public static class RegisterTokenRequest {
        @NotBlank(message = "FCM token is required")
        private String token;

        @NotBlank(message = "Device type is required")
        @Pattern(regexp = "IOS|ANDROID|WEB", message = "Device type must be IOS, ANDROID, or WEB")
        private String deviceType;
    }

    @Data
    public static class DeregisterTokenRequest {
        @NotBlank(message = "FCM token is required")
        private String token;
    }
}