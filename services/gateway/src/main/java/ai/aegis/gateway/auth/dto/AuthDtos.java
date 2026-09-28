package ai.aegis.gateway.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response DTOs for the auth endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
            @NotBlank String fullName,
            @NotBlank String organizationName
    ) {
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {
    }

    public record AuthResponse(
            String accessToken,
            String tokenType,
            String userId,
            String orgId,
            String email,
            String fullName,
            String role
    ) {
    }

    public record MeResponse(
            String userId,
            String orgId,
            String email,
            String fullName,
            String role
    ) {
    }
}
