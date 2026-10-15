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
 * Phase 2 domain: merchants, agents, physical devices, inventory lifecycle,
 * and the authorization boundaries around them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MerchantAgentDeviceIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(900000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.tracom.lipamdogo.repository.ProductRepository productRepository;

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String uniqueImei() {
        return String.format("355%012d", IMEI_SEQUENCE.incrementAndGet());
    }

    private String uniqueSerial() {
        return "SN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String registerAndLogin(String name, String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\": \"%s\", \"email\": \"%s\", \"password\": \"Secret123\"}"
                                .formatted(name, email)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"Secret123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private String loginAsAdmin() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"admin@tracom.local\", \"password\": \"Admin@1234\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private String loginAs(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private Long createMerchant(String adminToken, String businessName) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/merchants")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "businessName": "%s",
                                  "phone": "+254700009999",
                                  "email": "%s@example.com",
                                  "address": "Nairobi"
                                }
                                """.formatted(businessName, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantCode").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long createStaffUser(String adminToken, String name, String role) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "%s",
                                  "email": "%s",
                                  "password": "Secret123",
                                  "role": "%s"
                                }
                                """.formatted(name, uniqueEmail(name.replaceAll("\\s+", "").toLowerCase()), role)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value(role))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long createAgent(String adminToken, Long userAccountId, Long merchantId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/agents")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccountId\": %d, \"merchantId\": %d}".formatted(userAccountId, merchantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentCode").isNotEmpty())
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long createDevice(String adminToken, Long productId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId, uniqueImei(), uniqueSerial())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCode").isNotEmpty())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long userIdFromToken(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(me.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void adminCreatesMerchantAndCustomersCannot() throws Exception {
        String adminToken = loginAsAdmin();
        String customerToken = registerAndLogin("Shop Customer", uniqueEmail("shopper"));

        Long merchantId = createMerchant(adminToken, "Riverside Phones " + UUID.randomUUID());

        mockMvc.perform(get("/api/v1/admin/merchants/{id}", merchantId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentCount").value(0))
                .andExpect(jsonPath("$.deviceCount").value(0));

        // Suspend the merchant.
        mockMvc.perform(patch("/api/v1/admin/merchants/{id}", merchantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // Customers cannot create or modify merchants.
        mockMvc.perform(post("/api/v1/admin/merchants")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\": \"Fake\", \"phone\": \"1\", \"email\": \"f@x.com\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/admin/merchants/{id}", merchantId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isForbidden());

        // Unauthenticated callers are rejected before they reach the logic.
        mockMvc.perform(get("/api/v1/admin/merchants")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCreatesAgentLinkedToAccountAndMerchant() throws Exception {
        String adminToken = loginAsAdmin();
        Long merchantId = createMerchant(adminToken, "Agent House " + UUID.randomUUID());
        Long agentUserId = createStaffUser(adminToken, "Field Agent", "AGENT");

        MvcResult created = mockMvc.perform(post("/api/v1/admin/agents")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccountId\": %d, \"merchantId\": %d}".formatted(agentUserId, merchantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userAccountId").value(agentUserId))
                .andExpect(jsonPath("$.merchantId").value(merchantId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        Long agentId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        // Agent belongs to the correct merchant.
        mockMvc.perform(get("/api/v1/admin/agents?merchantId={id}", merchantId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(agentId)).exists());

        // One profile per account.
        mockMvc.perform(post("/api/v1/admin/agents")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccountId\": %d, \"merchantId\": %d}".formatted(agentUserId, merchantId)))
                .andExpect(status().isBadRequest());

        // Non-AGENT accounts cannot back an agent profile.
        Long customerUserId = createStaffUser(adminToken, "Plain Customer", "CUSTOMER");
        mockMvc.perform(post("/api/v1/admin/agents")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccountId\": %d, \"merchantId\": %d}".formatted(customerUserId, merchantId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deviceCreationEnforcesUniquenessAndFormat() throws Exception {
        String adminToken = loginAsAdmin();
        Long productId = productRepository.findAll().get(0).getId();
        String imei = uniqueImei();
        String serial = uniqueSerial();

        mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId, imei, serial)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.productId").value(productId));

        // Duplicate IMEI rejected.
        mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId, imei, uniqueSerial())))
                .andExpect(status().isBadRequest());

        // Duplicate serial rejected.
        mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId, uniqueImei(), serial)))
                .andExpect(status().isBadRequest());

        // Malformed IMEI rejected by validation.
        mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"not-an-imei\"}".formatted(productId)))
                .andExpect(status().isBadRequest());

        // Customers cannot touch inventory.
        String customerToken = registerAndLogin("Inventory Browser", uniqueEmail("browser"));
        mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d}".formatted(productId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void deviceLifecycleAssignmentAndTransitions() throws Exception {
        String adminToken = loginAsAdmin();
        Long productId = productRepository.findAll().get(0).getId();
        Long merchantId = createMerchant(adminToken, "Lifecycle Store " + UUID.randomUUID());
        // Activate the merchant so it can receive devices.
        mockMvc.perform(patch("/api/v1/admin/merchants/{id}", merchantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isOk());

        Long deviceId = createDevice(adminToken, productId);
        String customerToken = registerAndLogin("Lifecycle Customer", uniqueEmail("lifecycle"));
        Long customerId = userIdFromToken(customerToken);

        // AVAILABLE -> merchant channel.
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\": %d}".formatted(merchantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.merchantId").value(merchantId));

        // ASSIGNED -> customer financing.
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": %d}".formatted(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINANCED"))
                .andExpect(jsonPath("$.customerId").value(customerId));

        // FINANCED -> AVAILABLE directly is illegal.
        mockMvc.perform(post("/api/v1/admin/devices/{id}/release", deviceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        // FINANCED -> PAID_OFF is legal; PAID_OFF is terminal.
        mockMvc.perform(patch("/api/v1/admin/devices/{id}/status", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PAID_OFF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID_OFF"));
        mockMvc.perform(patch("/api/v1/admin/devices/{id}/status", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"AVAILABLE\"}"))
                .andExpect(status().isBadRequest());

        // Status endpoint cannot be abused for relationship changes.
        Long freshDevice = createDevice(adminToken, productId);
        mockMvc.perform(patch("/api/v1/admin/devices/{id}/status", freshDevice)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"FINANCED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void suspendedMerchantAndSuspendedAgentCannotReceiveDevices() throws Exception {
        String adminToken = loginAsAdmin();
        Long productId = productRepository.findAll().get(0).getId();
        Long merchantId = createMerchant(adminToken, "Suspended Store " + UUID.randomUUID());
        Long agentUserId = createStaffUser(adminToken, "Suspended Agent", "AGENT");
        Long agentId = createAgent(adminToken, agentUserId, merchantId);
        Long deviceId = createDevice(adminToken, productId);

        // Merchant is PENDING by default: assignment refused until ACTIVE.
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\": %d}".formatted(merchantId)))
                .andExpect(status().isBadRequest());

        // Activate merchant, then suspend the agent: agent assignment refused.
        mockMvc.perform(patch("/api/v1/admin/merchants/{id}", merchantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/agents/{id}", agentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SUSPENDED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\": %d}".formatted(agentId)))
                .andExpect(status().isBadRequest());

        // Suspended merchant cannot receive devices either.
        mockMvc.perform(patch("/api/v1/admin/merchants/{id}", merchantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SUSPENDED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\": %d}".formatted(merchantId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scopedDeviceAccessEnforcesOwnership() throws Exception {
        String adminToken = loginAsAdmin();
        Long productId = productRepository.findAll().get(0).getId();

        // Two merchants, two customers, devices handed to each customer.
        Long merchantA = createMerchant(adminToken, "Scope Store A " + UUID.randomUUID());
        Long merchantB = createMerchant(adminToken, "Scope Store B " + UUID.randomUUID());
        for (Long merchantId : List.of(merchantA, merchantB)) {
            mockMvc.perform(patch("/api/v1/admin/merchants/{id}", merchantId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"ACTIVE\"}"))
                    .andExpect(status().isOk());
        }
        String tokenA = registerAndLogin("Scope Customer A", uniqueEmail("scope-a"));
        String tokenB = registerAndLogin("Scope Customer B", uniqueEmail("scope-b"));
        Long customerA = userIdFromToken(tokenA);
        Long customerB = userIdFromToken(tokenB);

        Long deviceA = createDevice(adminToken, productId);
        Long deviceB = createDevice(adminToken, productId);
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceA)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\": %d}".formatted(merchantA)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceA)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": %d}".formatted(customerA)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceB)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\": %d}".formatted(merchantB)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceB)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": %d}".formatted(customerB)))
                .andExpect(status().isOk());

        // Customer A sees exactly their own device; B's device is invisible.
        mockMvc.perform(get("/api/v1/customer/devices")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(deviceA));
        mockMvc.perform(get("/api/v1/customer/devices/{id}", deviceB)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        // Seeded cross-merchant isolation: Amina (QuickMart) cannot see TechPoint's device.
        String aminaToken = loginAs("amina.agent@tracom.local", "Agent@1234");
        MvcResult aminaDevices = mockMvc.perform(get("/api/v1/agents/me/devices")
                        .header("Authorization", "Bearer " + aminaToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Integer> aminaIds = JsonPath.read(aminaDevices.getResponse().getContentAsString(), "$[*].id");
        assertThat(aminaIds).doesNotContain(deviceB.intValue());

        mockMvc.perform(get("/api/v1/agents/me/devices/{id}", deviceB)
                        .header("Authorization", "Bearer " + aminaToken))
                .andExpect(status().isNotFound());

        // Merchants are confined to their own scope as well.
        String quickMartToken = loginAs("quickmart@tracom.local", "Merchant@1234");
        MvcResult merchantDevices = mockMvc.perform(get("/api/v1/merchants/me/devices")
                        .header("Authorization", "Bearer " + quickMartToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Integer> merchantIds = JsonPath.read(merchantDevices.getResponse().getContentAsString(), "$[*].id");
        assertThat(merchantIds).doesNotContain(deviceB.intValue());

        // Agents have no merchant scope; merchants have no agent scope.
        mockMvc.perform(get("/api/v1/merchants/me/devices")
                        .header("Authorization", "Bearer " + aminaToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/agents/me")
                        .header("Authorization", "Bearer " + quickMartToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCanHoldMultipleDevices() throws Exception {
        String adminToken = loginAsAdmin();
        Long productId = productRepository.findAll().get(0).getId();
        Long merchantId = createMerchant(adminToken, "Multi Device Store " + UUID.randomUUID());
        mockMvc.perform(patch("/api/v1/admin/merchants/{id}", merchantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isOk());

        String token = registerAndLogin("Multi Device Owner", uniqueEmail("multi-device"));
        Long customerId = userIdFromToken(token);

        for (int i = 0; i < 3; i++) {
            Long deviceId = createDevice(adminToken, productId);
            mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"merchantId\": %d}".formatted(merchantId)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/admin/devices/{id}/assign", deviceId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"customerId\": %d}".formatted(customerId)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/customer/devices")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void adminCanViewAndFilterInventory() throws Exception {
        String adminToken = loginAsAdmin();

        mockMvc.perform(get("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Seeded AVAILABLE devices exist.
        MvcResult available = mockMvc.perform(get("/api/v1/admin/devices?status=AVAILABLE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        List<String> codes = JsonPath.read(available.getResponse().getContentAsString(), "$[*].deviceCode");
        assertThat(codes).contains("TRC-000129");

        // Seed responses carry no sensitive fields.
        mockMvc.perform(get("/api/v1/admin/merchants")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].password").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/agents")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }
}
