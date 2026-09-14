package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.AuthResponse;
import com.mockinterview.backend.dto.GuestLoginRequest;
import com.mockinterview.backend.dto.LoginRequest;
import com.mockinterview.backend.dto.RegisterRequest;
import com.mockinterview.backend.entity.Role;
import com.mockinterview.backend.entity.Token;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.TokenRepository;
import com.mockinterview.backend.repository.UserRepository;
import com.mockinterview.backend.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("An account with this email already exists");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        user.setRole(Role.USER);
        userRepository.save(user);

        return issueToken(user);
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        user.setLastActiveAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        return issueToken(user);
    }

    /** Logs a browser back in as its guest account (creating one on first call), identified by a
     *  client-generated guestId (localStorage) rather than credentials. Reusing the same account on
     *  repeat calls — instead of minting a new one each time — is what lets the one-attempt cap in
     *  InterviewSessionService.startSession actually stick. */
    public AuthResponse guestLogin(GuestLoginRequest request) {
        User user = userRepository.findByGuestId(request.guestId()).orElseGet(() -> {
            User guest = new User();
            guest.setEmail("guest-" + request.guestId() + "@guest.local");
            guest.setPasswordHash(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
            guest.setDisplayName("Guest");
            guest.setRole(Role.USER);
            guest.setGuest(true);
            guest.setGuestId(request.guestId());
            return guest;
        });

        user.setLastActiveAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        return issueToken(user);
    }

    private AuthResponse issueToken(User user) {
        String jwt = jwtUtil.generateToken(user);

        Token token = new Token();
        token.setTokenValue(jwt);
        token.setUser(user);
        tokenRepository.save(token);

        return new AuthResponse(jwt, user.getEmail(), user.getDisplayName(), user.getRole().name(), user.isGuest());
    }
}
