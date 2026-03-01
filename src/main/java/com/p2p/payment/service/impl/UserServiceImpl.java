package com.p2p.payment.service.impl;

import com.p2p.payment.domain.entity.User;
import com.p2p.payment.dto.request.LoginRequest;
import com.p2p.payment.dto.request.RegisterRequest;
import com.p2p.payment.dto.response.AuthResponse;
import com.p2p.payment.dto.response.UserResponse;
import com.p2p.payment.exception.ResourceNotFoundException;
import com.p2p.payment.exception.TransferException;
import com.p2p.payment.notification.service.NotificationPublisher;
import com.p2p.payment.repository.UserRepository;
import com.p2p.payment.security.JwtService;
import com.p2p.payment.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final NotificationPublisher notificationPublisher;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new TransferException("Email address is already registered");
        }

        var user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .build();

        userRepository.save(user);
        log.info("Registered new user id={}", user.getId());

        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return buildAuthResponse(token, user);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));

        var user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // --- New IP detection ---
        // Store the first IP seen per user in Redis with a 30-day TTL.
        // On subsequent logins from a different IP, publish a security alert.
        String clientIp = resolveClientIp();
        String knownIpKey = "known-ip:" + user.getId() + ":" + clientIp;

        boolean isNewIp = Boolean.FALSE.equals(redisTemplate.hasKey(knownIpKey));
        if (isNewIp) {
            // Record this IP as known for 30 days
            redisTemplate.opsForValue().set(knownIpKey, "1", Duration.ofDays(30));

            // Only fire the alert for existing accounts (not first-ever login)
            if (user.getCreatedAt() != null) {
                notificationPublisher.publishLoginFromNewIp(
                        user.getId(),
                        user.getEmail(),
                        user.getFullName(),
                        clientIp,
                        "Unknown" // In production: resolve via MaxMind GeoIP
                );
                log.info("New IP login detected userId={} ip={}", user.getId(), clientIp);
            }
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return buildAuthResponse(token, user);
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new TransferException("Current password is incorrect");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Publish security notification — Push + Email
        notificationPublisher.publishPasswordChanged(
                user.getId(),
                user.getEmail(),
                user.getFullName()
        );

        log.info("Password changed for userId={}", userId);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        var user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        return toResponse(user);
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "unknown";
            HttpServletRequest request = attrs.getRequest();
            // Respect X-Forwarded-For from load balancer
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private AuthResponse buildAuthResponse(String token, User user) {
        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresInMs(jwtService.getExpirationMs())
                .user(toResponse(user))
                .build();
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .createdAt(user.getCreatedAt())
                .build();
    }
}