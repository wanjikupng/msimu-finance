package com.tracom.lipamdogo.dto;

import com.tracom.lipamdogo.model.Role;

/**
 * Returned after a successful login. Contains only what the frontend needs to
 * render the session. Never includes passwords, hashes, or security internals.
 */
public record AuthResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        Long userId,
        String customerCode,
        String fullName,
        Role role
) {
}
