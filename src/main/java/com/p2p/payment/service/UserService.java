package com.p2p.payment.service;

import com.p2p.payment.dto.request.LoginRequest;
import com.p2p.payment.dto.request.RegisterRequest;
import com.p2p.payment.dto.response.AuthResponse;
import com.p2p.payment.dto.response.UserResponse;

import java.util.UUID;

public interface UserService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    UserResponse getById(UUID id);
}
