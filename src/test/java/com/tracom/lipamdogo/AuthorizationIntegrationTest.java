package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server-side enforcement of the authorization model: unauthenticated callers
 * are rejected, customers are confined to their own resources, and staff-only
 * operations return 403 for customers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.tracom.lipamdogo.repository.ProductRepository productRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.PaymentPlanRepository paymentPlanRepository;

    private static final java.util.concurrent.atomic.AtomicLong AUTHZ_IMEI =
            new java.util.concurrent.atomic.AtomicLong(500000);

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String registerAndLogin(String name, String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "%s",
                                  "email": "%s",
                                  "password": "Secret123"
                                }
                                """.formatted(name, email)))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Secret123"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private String loginAsAdmin() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "admin@tracom.local",
                                  "password": "Admin@1234"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private Long userIdFromToken(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(me.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void unauthenticatedRequestsToProtectedEndpointsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/loans/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/loans")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/devices")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/financing/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\": 1, \"depositAmount\": 4000, \"paymentPlanId\": 1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/financing/applications"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": 1, \"amount\": 100, \"channel\": \"MPESA\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/cash-loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountRequested\": 1000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/loans/me")
                        .header("Authorization", "Bearer this-is-not-a-valid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCanAccessOwnDashboardButNotAnotherCustomers() throws Exception {
        String tokenA = registerAndLogin("Customer Alpha", uniqueEmail("alpha"));
        String tokenB = registerAndLogin("Customer Beta", uniqueEmail("beta"));
        Long idB = userIdFromToken(tokenB);

        mockMvc.perform(get("/api/v1/users/me/dashboard")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.fullName").value("Customer Alpha"))
                .andExpect(jsonPath("$.customer.password").doesNotExist());

        // Customer A addressing Customer B's dashboard: must be forbidden.
        mockMvc.perform(get("/api/v1/users/{id}/dashboard", idB)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }

    private String createOfficer(String adminToken) throws Exception {
        String email = uniqueEmail("authz-officer");
        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Authz Officer", "email": "%s",
                                 "password": "Secret123", "role": "LOAN_OFFICER"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"Secret123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private Long createDevice(String adminToken) throws Exception {
        Long productId = productRepository.findAll().get(0).getId();
        MvcResult created = mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId,
                                        String.format("354%012d", AUTHZ_IMEI.incrementAndGet()),
                                        "AZ-" + UUID.randomUUID().toString().replace("-", "")
                                                .substring(0, 10).toUpperCase())))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long approveContractFor(String customerToken, String officerToken, Long deviceId) throws Exception {
        Long planId = paymentPlanRepository.findAll().get(0).getId();
        MvcResult submitted = mockMvc.perform(post("/api/v1/financing/applications")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\": %d, \"depositAmount\": 4000, \"paymentPlanId\": %d}"
                                .formatted(deviceId, planId)))
                .andExpect(status().isOk())
                .andReturn();
        Long applicationId = ((Number) JsonPath.read(
                submitted.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/review", applicationId)
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk());
        MvcResult approved = mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/approve", applicationId)
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(approved.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void customerCanPayOwnApprovedContractButNotAnothers() throws Exception {
        String tokenA = registerAndLogin("Payer Alpha", uniqueEmail("payer-a"));
        String tokenB = registerAndLogin("Payer Beta", uniqueEmail("payer-b"));
        String adminToken = loginAsAdmin();
        String officerToken = createOfficer(adminToken);

        // B's contract exists only because an officer approved B's application.
        Long contractB = approveContractFor(tokenB, officerToken, createDevice(adminToken));

        // A cannot pay B's contract.
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": 70, \"channel\": \"MPESA\"}".formatted(contractB)))
                .andExpect(status().isForbidden());

        // A cannot read B's contract.
        mockMvc.perform(get("/api/v1/loans/{id}", contractB)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());

        // A cannot even see B's transactions.
        mockMvc.perform(get("/api/v1/admin/payments")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());

        // B can pay and read their own contract.
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": 70, \"channel\": \"MPESA\"}".formatted(contractB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.receiptNumber").isNotEmpty());
    }

    @Test
    void staffOnlyOperationsRejectCustomersAndAllowAdmin() throws Exception {
        String customerToken = registerAndLogin("Regular Customer", uniqueEmail("regular"));
        String adminToken = loginAsAdmin();
        Long customerId = userIdFromToken(customerToken);

        // Customer is forbidden from staff-only reads and writes.
        mockMvc.perform(get("/api/v1/loans")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Nope", "fullPrice": 1000, "downPayment": 100, "dailyAmount": 10}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/payment-modes")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modeName\": \"MODE-%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        // Admin is allowed.
        mockMvc.perform(get("/api/v1/loans")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/{id}/dashboard", customerId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/payment-modes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modeName\": \"MODE-%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void approvalCreatesContractForTheApplicant() throws Exception {
        String customerToken = registerAndLogin("Originated Customer", uniqueEmail("originated"));
        Long customerId = userIdFromToken(customerToken);
        String adminToken = loginAsAdmin();
        String officerToken = createOfficer(adminToken);

        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        mockMvc.perform(get("/api/v1/loans/{id}", contractId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.contractNumber").isNotEmpty());
    }
}
