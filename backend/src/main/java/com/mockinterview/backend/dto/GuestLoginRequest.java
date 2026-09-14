package com.mockinterview.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record GuestLoginRequest(@NotBlank String guestId) {
}
