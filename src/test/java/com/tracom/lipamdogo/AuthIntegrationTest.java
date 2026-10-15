package com.tracom.lipamdogo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.tracom.lipamdogo.repository.UserAccountRepository userAccountRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private String uniqueEmail() {
        return "auth-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void registerHashesPasswordAndNeverReturnsIt() throws Exception {
        String email = uniqueEmail();
        String rawPassword = "Secret123";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Wanjiru Kamau",
                                  "email": "%s",
                                  "phoneNumber": "+254700000010",
                                  "password": "%s"
                                }
                                """.formatted(email, rawPassword)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.customerCode").exists())
                .andExpect(jsonPath("$.fullName").value("Wanjiru Kamau"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        var stored = userAccountRepository.findByEmail(email).orElseThrow();
        assert stored.getPassword() != null;
        org.assertj.core.api.Assertions.assertThat(stored.getPassword()).doesNotContain(rawPassword);
        org.assertj.core.api.Assertions.assertThat(passwordEncoder.matches(rawPassword, stored.getPassword())).isTrue();
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        String email = uniqueEmail();
        String body = """
                {
                  "fullName": "Duplicate User",
                  "email": "%s",
                  "phoneNumber": "+254700000011",
                  "password": "Secret123"
                }
                """.formatted(email);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerRejectsWeakCredentials() throws Exception {
        // Too short.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Weak Password",
                                  "email": "%s",
                                  "password": "short1"
                                }
                                """.formatted(uniqueEmail())))
                .andExpect(status().isBadRequest());

        // No digit.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Weak Password",
                                  "email": "%s",
                                  "password": "abcdefgh"
                                }
                                """.formatted(uniqueEmail())))
                .andExpect(status().isBadRequest());

        // Missing name.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "",
                                  "email": "%s",
                                  "password": "Secret123"
                                }
                                """.formatted(uniqueEmail())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginSucceedsWithCorrectCredentialsAndReturnsToken() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Login User",
                                  "email": "%s",
                                  "password": "Secret123"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Secret123"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.customerCode").exists())
                .andExpect(jsonPath("$.fullName").value("Login User"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void loginRejectsWrongPasswordWithoutRevealingAccountState() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Locked Out",
                                  "email": "%s",
                                  "password": "Secret123"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Wrong1234"
                                }
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "nobody-%s@example.com",
                                  "password": "Wrong1234"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }
}
