package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that one customer can hold several applications and
 * contracts across several physical devices — through the approval workflow.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MultiContractIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(600000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.tracom.lipamdogo.repository.ProductRepository productRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.PaymentPlanRepository paymentPlanRepository;

    @Test
    void customerCanHoldMultipleContractsAcrossMultipleDevices() throws Exception {
        String email = "multi-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Multi Device",
                                  "email": "%s",
                                  "password": "Secret123"
                                }
                                """.formatted(email)))
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
        String token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");

        String adminToken = JsonPath.<String>read(
                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\": \"admin@tracom.local\", \"password\": \"Admin@1234\"}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.token");
        String officerEmail = "multi-officer-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Multi Officer", "email": "%s",
                                 "password": "Secret123", "role": "LOAN_OFFICER"}
                                """.formatted(officerEmail)))
                .andExpect(status().isCreated());
        String officerToken = JsonPath.<String>read(
                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\": \"%s\", \"password\": \"Secret123\"}".formatted(officerEmail)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.token");

        Long planId = paymentPlanRepository.findAll().get(0).getId();
        Long productId = productRepository.findAll().get(0).getId();
        Long firstDevice = createDevice(adminToken, productId);
        Long secondDevice = createDevice(adminToken, productId);

        Long firstContract = applyAndApprove(token, officerToken, firstDevice, planId);
        Long secondContract = applyAndApprove(token, officerToken, secondDevice, planId);
        assertThat(firstContract).isNotEqualTo(secondContract);

        MvcResult mine = mockMvc.perform(get("/api/v1/loans/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();
        List<Integer> ids = JsonPath.read(mine.getResponse().getContentAsString(), "$[*].id");
        assertThat(ids).containsExactlyInAnyOrder(firstContract.intValue(), secondContract.intValue());

        // A payment to the first contract leaves the second untouched.
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": 14000, \"channel\": \"MPESA\"}".formatted(firstContract)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionReference").isNotEmpty())
                .andExpect(jsonPath("$.status").value("POSTED"));

        mockMvc.perform(get("/api/v1/loans/{id}", secondContract)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanStatus").value("ACTIVE"));
    }

    private Long createDevice(String adminToken, Long productId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId,
                                        String.format("353%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "MC-" + UUID.randomUUID().toString().replace("-", "")
                                                .substring(0, 10).toUpperCase())))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long applyAndApprove(String customerToken, String officerToken,
                                 Long deviceId, Long planId) throws Exception {
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
}
