package com.tracom.lipamdogo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deployment surface: what the Render (and Docker) deployment exposes without a
 * token, and what it must keep closed.
 *
 * <p>The frontend is packaged into the jar and served by Spring Boot, so this
 * is the contract the public URL relies on: pages and assets load, the health
 * probe answers, the login endpoint works, and every data endpoint still
 * requires a Bearer token.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeploymentSurfaceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointIsPublicAndChecksTheDatabase() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.readiness").value("ACCEPTING_TRAFFIC"))
                .andExpect(jsonPath("$.application").value("lipa-mdogo-mdogo"));
    }

    @Test
    void frontendPagesAndAssetsAreServedWithoutAuthentication() throws Exception {
        // "/" is the servlet welcome page: it forwards to index.html.
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("M-Simu Finance")));

        mockMvc.perform(get("/login.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in")));

        // The portal shell is public HTML; its data is not. Configuration must
        // never fall back to an absolute localhost API host.
        mockMvc.perform(get("/js/config.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"/api/v1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("http://localhost:8080/api/v1"))));

        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedApiStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/admin/payments"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/users/me/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void seededDemoCredentialsCanSignIn() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@tracom.local","password":"Admin@1234"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }
}
