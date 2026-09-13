package com.mockinterview.backend.dto;

public record AuthResponse(String token, String email, String displayName, String role) {
}
