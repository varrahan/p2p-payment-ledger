package com.p2p.payment.security;

import com.p2p.payment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Standalone UserDetailsService bean.
 *
 * Previously this was defined as an inline @Bean inside SecurityConfig,
 * which caused a circular dependency:
 *
 *   JwtAuthenticationFilter → UserDetailsService (defined in SecurityConfig)
 *   SecurityConfig → JwtAuthenticationFilter
 *
 * Extracting it here as a @Service breaks the cycle. SecurityConfig and
 * JwtAuthenticationFilter both depend on this bean, but this bean depends
 * on nothing in the security layer — only UserRepository.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        return new UserPrincipal(user);
    }
}