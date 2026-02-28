package com.p2p.payment.controller;

import com.p2p.payment.dto.request.LoginRequest;
import com.p2p.payment.dto.request.RegisterRequest;
import com.p2p.payment.dto.response.ApiResponse;
import com.p2p.payment.dto.response.AuthResponse;
import com.p2p.payment.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        var response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("User registered successfully", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        var response = userService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("Login successful", response));
    }
}
