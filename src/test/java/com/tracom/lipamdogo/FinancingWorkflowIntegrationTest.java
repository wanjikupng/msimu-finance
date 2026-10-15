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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 financing lifecycle: submit → review → approve/reject, contract
 * snapshot integrity, device integration, and ownership enforcement.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinancingWorkflowIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(300000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.tracom.lipamdogo.repository.ProductRepository productRepository;

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String uniqueImei() {
        return String.format("356%012d", IMEI_SEQUENCE.incrementAndGet());
    }

    private String uniqueSerial() {
        return "FW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String registerAndLogin(String name, String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\": \"%s\", \"email\": \"%s\", \"password\": \"Secret123\"}"
                                .formatted(name, email)))
                .andExpect(status().isCreated());
        return loginAs(email, "Secret123");
    }

    private String loginAs(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private String loginAsAdmin() throws Exception {
        return loginAs("admin@tracom.local", "Admin@1234");
    }

    private String createOfficer(String adminToken) throws Exception {
        String email = uniqueEmail("officer");
        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Loan Officer", "email": "%s",
                                 "password": "Secret123", "role": "LOAN_OFFICER"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        return loginAs(email, "Secret123");
    }

    private Long createDevice(String adminToken, Long productId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId, uniqueImei(), uniqueSerial())))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long classicPlanId(String token) throws Exception {
        MvcResult plans = mockMvc.perform(get("/api/v1/financing/payment-plans")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        List<String> names = JsonPath.read(plans.getResponse().getContentAsString(), "$[*].planName");
        List<Integer> ids = JsonPath.read(plans.getResponse().getContentAsString(), "$[*].id");
        for (int i = 0; i < names.size(); i++) {
            if ("Flex 200 Days".equals(names.get(i))) {
                return ids.get(i).longValue();
            }
        }
        throw new IllegalStateException("Classic plan missing from seed");
    }

    private MvcResult submit(String token, Long deviceId, String deposit, Long planId) throws Exception {
        return mockMvc.perform(post("/api/v1/financing/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\": %d, \"depositAmount\": %s, \"paymentPlanId\": %d}"
                                .formatted(deviceId, deposit, planId)))
                .andReturn();
    }

    private Long submitExpectOk(String token, Long deviceId, String deposit, Long planId) throws Exception {
        MvcResult result = submit(token, deviceId, deposit, planId);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void review(String officerToken, Long applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/review", applicationId)
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    private MvcResult approve(String officerToken, Long applicationId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/approve", applicationId)
                        .header("Authorization", "Bearer " + officerToken))
                .andReturn();
    }

    private Long galaxyProductId() {
        return productRepository.findByName("Samsung Galaxy A15").orElseThrow().getId();
    }

    @Test
    void submitCreatesApplicationAndReservesDevice() throws Exception {
        String adminToken = loginAsAdmin();
        String customerToken = registerAndLogin("Applicant One", uniqueEmail("applicant"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(customerToken);

        MvcResult result = submit(customerToken, deviceId, "4000", planId);
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body, "$.applicationNumber")).startsWith("APP-");
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("SUBMITTED");
        assertThat(JsonPath.<Number>read(body, "$.requestedPrincipal").intValue()).isEqualTo(14000);
        assertThat(JsonPath.<Number>read(body, "$.depositAmount").intValue()).isEqualTo(4000);
        assertThat(body).doesNotContain("password");

        mockMvc.perform(get("/api/v1/admin/devices/{id}", deviceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    void duplicateActiveApplicationForSameDeviceIsRejected() throws Exception {
        String adminToken = loginAsAdmin();
        String tokenA = registerAndLogin("Dup Customer A", uniqueEmail("dup-a"));
        String tokenB = registerAndLogin("Dup Customer B", uniqueEmail("dup-b"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(tokenA);

        submitExpectOk(tokenA, deviceId, "4000", planId);

        MvcResult second = submit(tokenB, deviceId, "4000", planId);
        assertThat(second.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void submitValidatesFinancialInput() throws Exception {
        String adminToken = loginAsAdmin();
        String token = registerAndLogin("Validation Customer", uniqueEmail("validation"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(token);

        // Negative deposit.
        assertThat(submit(token, deviceId, "-100", planId).getResponse().getStatus()).isEqualTo(400);
        // Deposit at/above price (18000).
        assertThat(submit(token, deviceId, "18000", planId).getResponse().getStatus()).isEqualTo(400);
        // Below product minimum (4000).
        assertThat(submit(token, deviceId, "3999", planId).getResponse().getStatus()).isEqualTo(400);
        // Missing device.
        MvcResult missing = mockMvc.perform(post("/api/v1/financing/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\": 999999999, \"depositAmount\": 4000, \"paymentPlanId\": %d}"
                                .formatted(planId)))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        // Unknown plan.
        assertThat(submit(token, deviceId, "4000", 999999999L).getResponse().getStatus()).isEqualTo(400);

        // Ineligible (non-AVAILABLE) device.
        Long assignedId = createDevice(adminToken, galaxyProductId());
        MvcResult merchantCreated = mockMvc.perform(post("/api/v1/admin/merchants")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName": "Validation Store", "phone": "+254700009001",
                                 "email": "validation-%s@example.com"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn();
        Long merchantId = ((Number) JsonPath.read(
                merchantCreated.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(patch("/api/v1/admin/merchants/{id}", merchantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", assignedId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\": %d}".formatted(merchantId)))
                .andExpect(status().isOk());
        assertThat(submit(token, assignedId, "4000", planId).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void reviewThenApproveCreatesContractWithSnapshot() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createOfficer(adminToken);
        String customerToken = registerAndLogin("Approved Customer", uniqueEmail("approved"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(customerToken);

        Long applicationId = submitExpectOk(customerToken, deviceId, "4000", planId);

        // Approval requires UNDER_REVIEW first.
        MvcResult early = approve(officerToken, applicationId);
        assertThat(early.getResponse().getStatus()).isEqualTo(400);

        review(officerToken, applicationId);

        MvcResult approved = approve(officerToken, applicationId);
        String contractBody = approved.getResponse().getContentAsString();
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(contractBody, "$.contractNumber")).startsWith("LN-");
        assertThat((String) JsonPath.read(contractBody, "$.loanStatus")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Number>read(contractBody, "$.principalAmount").intValue()).isEqualTo(14000);
        assertThat(JsonPath.<Number>read(contractBody, "$.totalPayable").intValue()).isEqualTo(14000);
        assertThat(JsonPath.<Number>read(contractBody, "$.amountRemaining").intValue()).isEqualTo(14000);
        assertThat(JsonPath.<Number>read(contractBody, "$.dailyAmount").intValue()).isEqualTo(70);
        assertThat(JsonPath.<Number>read(contractBody, "$.deviceId").longValue()).isEqualTo(deviceId);
        assertThat((String) JsonPath.read(contractBody, "$.planName")).isEqualTo("Flex 200 Days");
        assertThat((String) JsonPath.read(contractBody, "$.paymentFrequency")).isEqualTo("DAILY");

        // Application is APPROVED and linked.
        mockMvc.perform(get("/api/v1/financing/applications/me/{id}", applicationId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedByName").value("Loan Officer"));

        // Device handed to the customer as FINANCED.
        mockMvc.perform(get("/api/v1/admin/devices/{id}", deviceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINANCED"))
                .andExpect(jsonPath("$.customerName").value("Approved Customer"));

        // Customer sees the contract with approved terms.
        mockMvc.perform(get("/api/v1/loans/me")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].contractNumber").isNotEmpty());

        // Double approval is rejected.
        MvcResult again = approve(officerToken, applicationId);
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void rejectStoresReasonReleasesDeviceAndCreatesNoContract() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createOfficer(adminToken);
        String customerToken = registerAndLogin("Rejected Customer", uniqueEmail("rejected"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(customerToken);

        Long applicationId = submitExpectOk(customerToken, deviceId, "4000", planId);
        review(officerToken, applicationId);

        // Reason is mandatory.
        mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/reject", applicationId)
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/reject", applicationId)
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionReason\": \"Required deposit not met\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("Required deposit not met"));

        mockMvc.perform(get("/api/v1/admin/devices/{id}", deviceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        mockMvc.perform(get("/api/v1/loans/me")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void customerCanCancelUndecidedApplication() throws Exception {
        String adminToken = loginAsAdmin();
        String customerToken = registerAndLogin("Cancelling Customer", uniqueEmail("cancel"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(customerToken);

        Long applicationId = submitExpectOk(customerToken, deviceId, "4000", planId);

        mockMvc.perform(post("/api/v1/financing/applications/me/{id}/cancel", applicationId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/v1/admin/devices/{id}", deviceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void customersCannotDecideApplications() throws Exception {
        String adminToken = loginAsAdmin();
        String customerToken = registerAndLogin("Powerless Customer", uniqueEmail("powerless"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(customerToken);
        Long applicationId = submitExpectOk(customerToken, deviceId, "4000", planId);

        mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/review", applicationId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/approve", applicationId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/reject", applicationId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionReason\": \"self-approved\"}"))
                .andExpect(status().isForbidden());

        // Application untouched by the attempt.
        mockMvc.perform(get("/api/v1/financing/applications/me/{id}", applicationId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void applicationsAreInvisibleAcrossCustomers() throws Exception {
        String adminToken = loginAsAdmin();
        String tokenA = registerAndLogin("Private A", uniqueEmail("private-a"));
        String tokenB = registerAndLogin("Private B", uniqueEmail("private-b"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(tokenA);
        Long applicationId = submitExpectOk(tokenA, deviceId, "4000", planId);

        mockMvc.perform(get("/api/v1/financing/applications/me/{id}", applicationId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/financing/applications/me/{id}/cancel", applicationId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/financing/applications/me")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/financing/applications/me")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void approvalFailsWhenDeviceLeftReservation() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createOfficer(adminToken);
        String customerToken = registerAndLogin("Stale Customer", uniqueEmail("stale"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(customerToken);
        Long applicationId = submitExpectOk(customerToken, deviceId, "4000", planId);
        review(officerToken, applicationId);

        // Concurrent admin action moves the device out of the reservation.
        mockMvc.perform(patch("/api/v1/admin/devices/{id}/status", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RETIRED\"}"))
                .andExpect(status().isOk());

        MvcResult approved = approve(officerToken, applicationId);
        assertThat(approved.getResponse().getStatus()).isEqualTo(400);

        // No half-completed state: still UNDER_REVIEW, no contract.
        mockMvc.perform(get("/api/v1/financing/applications/me/{id}", applicationId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        mockMvc.perform(get("/api/v1/loans/me")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void financedDeviceBlocksFurtherApplications() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createOfficer(adminToken);
        String tokenA = registerAndLogin("First Financed", uniqueEmail("first-fin"));
        String tokenB = registerAndLogin("Second Financed", uniqueEmail("second-fin"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(tokenA);

        Long applicationId = submitExpectOk(tokenA, deviceId, "4000", planId);
        review(officerToken, applicationId);
        assertThat(approve(officerToken, applicationId).getResponse().getStatus()).isEqualTo(200);

        MvcResult second = submit(tokenB, deviceId, "4000", planId);
        assertThat(second.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void customerJourneyAcrossTwoDevices() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createOfficer(adminToken);
        String customerToken = registerAndLogin("Two Device", uniqueEmail("two-device"));
        Long planId = classicPlanId(customerToken);
        Long firstDevice = createDevice(adminToken, galaxyProductId());
        Long secondDevice = createDevice(adminToken, galaxyProductId());

        Long firstApp = submitExpectOk(customerToken, firstDevice, "4000", planId);
        Long secondApp = submitExpectOk(customerToken, secondDevice, "5000", planId);
        assertThat(firstApp).isNotEqualTo(secondApp);

        for (Long applicationId : List.of(firstApp, secondApp)) {
            review(officerToken, applicationId);
            assertThat(approve(officerToken, applicationId).getResponse().getStatus()).isEqualTo(200);
        }

        mockMvc.perform(get("/api/v1/loans/me")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/financing/applications/me")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void summaryReflectsWorkflowCounts() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createOfficer(adminToken);
        String customerToken = registerAndLogin("Summary Customer", uniqueEmail("summary"));
        Long planId = classicPlanId(customerToken);

        Long approvedApp = submitExpectOk(customerToken, createDevice(adminToken, galaxyProductId()), "4000", planId);
        review(officerToken, approvedApp);
        assertThat(approve(officerToken, approvedApp).getResponse().getStatus()).isEqualTo(200);

        Long rejectedApp = submitExpectOk(customerToken, createDevice(adminToken, galaxyProductId()), "4000", planId);
        review(officerToken, rejectedApp);
        mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/reject", rejectedApp)
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionReason\": \"Eligibility rules not met\"}"))
                .andExpect(status().isOk());

        submitExpectOk(customerToken, createDevice(adminToken, galaxyProductId()), "4000", planId);

        mockMvc.perform(get("/api/v1/admin/financing/applications/summary")
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.rejected").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.submitted").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.activeContracts").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // Customers cannot see the oversight summary.
        mockMvc.perform(get("/api/v1/admin/financing/applications/summary")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void quoteShowsServerComputedTerms() throws Exception {
        String adminToken = loginAsAdmin();
        String customerToken = registerAndLogin("Quote Customer", uniqueEmail("quote"));
        Long deviceId = createDevice(adminToken, galaxyProductId());
        Long planId = classicPlanId(customerToken);

        mockMvc.perform(get("/api/v1/financing/quote?deviceId={d}&paymentPlanId={p}&deposit={dep}",
                        deviceId, planId, "4000")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devicePrice").value(18000.0))
                .andExpect(jsonPath("$.requestedPrincipal").value(14000.0))
                .andExpect(jsonPath("$.installmentAmount").value(70.0))
                .andExpect(jsonPath("$.totalPayable").value(14000.0));
    }
}
