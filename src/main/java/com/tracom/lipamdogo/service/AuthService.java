package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.AuthResponse;
import com.tracom.lipamdogo.dto.UserLoginRequest;
import com.tracom.lipamdogo.dto.UserProfileResponse;
import com.tracom.lipamdogo.dto.UserRegistrationRequest;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import com.tracom.lipamdogo.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserAccountRepository userAccountRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * Self-service registration always creates a CUSTOMER. Elevated roles are
     * assigned out-of-band (bootstrap or an ADMIN operation in a later phase),
     * never from this endpoint.
     */
    @Transactional
    public UserProfileResponse register(UserRegistrationRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userAccountRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        UserAccount user = new UserAccount();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhoneNumber(request.phoneNumber() == null ? "" : request.phoneNumber().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(Role.CUSTOMER);
        user.setCustomerCode(generateCustomerCode());

        return UserProfileResponse.from(userAccountRepository.save(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(UserLoginRequest request) {
        String email = request.email().trim().toLowerCase();
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (AuthenticationException ex) {
            // Generic message on purpose: do not reveal whether the email exists.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        UserAccount account = userAccountRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));

        return new AuthResponse(
                jwtService.generateToken(account),
                "Bearer",
                jwtService.getExpirationSeconds(),
                account.getId(),
                account.getCustomerCode(),
                account.getFullName(),
                account.getRole()
        );
    }

    private String generateCustomerCode() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String code = "C-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            if (!userAccountRepository.existsByCustomerCode(code)) {
                return code;
            }
        }
        return "C-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
