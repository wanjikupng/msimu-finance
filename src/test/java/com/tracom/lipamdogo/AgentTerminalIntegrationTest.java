package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentPlanRepository;
import com.tracom.lipamdogo.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cashless agent terminals: an agent sends an M-PESA prompt to the customer's
 * phone; nothing is applied until the customer approves with their PIN. Plus
 * the terminal access rules and the terminal management console.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentTerminalIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(940000);
    private static final String TERMINAL = "TRM-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PaymentPlanRepository paymentPlanRepository;

    @Autowired
    private LoanContractRepository loanContractRepository;

    @Test
    void agentSendsMpesaPromptAndPaymentPostsOnlyWhenTheCustomerApproves() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String phone = randomPhone();
        String customer = createCustomer(admin, phone);
        Long contractId = approveContract(customer, admin, createDevice(admin));
        String agent = loginAs("amina.agent@tracom.local", "Agent@1234");

        String session = getJson(agent, "/api/v1/pos/" + TERMINAL + "/session");
        assertThat((String) JsonPath.read(session, "$.merchantName")).isEqualTo("QuickMart Electronics");

        // Look the customer up by the number they give at the counter (any format).
        String lookup = getJson(agent, "/api/v1/pos/" + TERMINAL + "/customers?query=" + phone.replace("0", " 0").trim());
        assertThat((String) JsonPath.read(lookup, "$.displayName")).isEqualTo("Terminal C.");
        assertThat((String) JsonPath.read(lookup, "$.maskedPhone")).contains("*****").doesNotContain(phone.substring(2, 7));
        assertThat(JsonPath.<Integer>read(lookup, "$.contracts[0].contractId").longValue()).isEqualTo(contractId);

        // Send the prompt to the customer's registered number: PENDING, and the balance has not moved.
        assertThat(lookup).doesNotContain(phone.substring(1));
        String pending = collect(agent, contractId, "70", null, UUID.randomUUID().toString());
        assertThat((String) JsonPath.read(pending, "$.payerMsisdn")).endsWith(phone.substring(7));
        String reference = JsonPath.read(pending, "$.transactionReference");
        assertThat((String) JsonPath.read(pending, "$.status")).isEqualTo("PENDING");
        assertThat((String) JsonPath.read(pending, "$.channel")).isEqualTo("MPESA");
        assertThat((String) JsonPath.read(pending, "$.origin")).isEqualTo("AGENT_TERMINAL");
        assertThat((String) JsonPath.read(pending, "$.terminalCode")).isEqualTo(TERMINAL);
        assertThat((String) JsonPath.read(pending, "$.agentName")).isEqualTo("Amina Yusuf");
        assertThat(loanContractRepository.findById(contractId).orElseThrow().getAmountRemaining())
                .isEqualByComparingTo("14000");

        // The customer approves with their M-PESA PIN: posted at once.
        String approved = respond(agent, reference, true);
        assertThat((String) JsonPath.read(approved, "$.status")).isEqualTo("POSTED");
        assertThat((String) JsonPath.read(approved, "$.receiptNumber")).startsWith("RCPT-");
        assertThat(loanContractRepository.findById(contractId).orElseThrow().getAmountRemaining())
                .isEqualByComparingTo("13930");

        // The slip carries the M-PESA reference and the phone's new unlock code.
        String receipt = getJson(agent, "/api/v1/pos/" + TERMINAL + "/collections/" + reference + "/receipt");
        assertThat((String) JsonPath.read(receipt, "$.mpesaReference")).isNotBlank();
        assertThat((String) JsonPath.read(receipt, "$.unlockCode")).matches("\\d{4} \\d{4} \\d{4}");
        assertThat((String) JsonPath.read(receipt, "$.merchantName")).isEqualTo("QuickMart Electronics");

        String summary = getJson(agent, "/api/v1/pos/" + TERMINAL + "/day-summary");
        assertThat((Integer) JsonPath.read(summary, "$.approvedCount")).isGreaterThanOrEqualTo(1);

        // A prompt can only be answered once.
        mockMvc.perform(post("/api/v1/pos/{t}/collections/{r}/customer-response", TERMINAL, reference)
                        .header("Authorization", "Bearer " + agent)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\": true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void declinedPromptAppliesNothingAndFreesTheAmount() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String phone = randomPhone();
        String customer = createCustomer(admin, phone);
        Long contractId = approveContract(customer, admin, createDevice(admin));
        String agent = loginAs("amina.agent@tracom.local", "Agent@1234");

        // The whole balance is held while the prompt is open...
        String first = collect(agent, contractId, "14000", phone, UUID.randomUUID().toString());
        mockMvc.perform(post("/api/v1/pos/{t}/collections", TERMINAL)
                        .header("Authorization", "Bearer " + agent)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collectBody(contractId, "100", phone)))
                .andExpect(status().isBadRequest());

        // ...and released when the customer declines.
        String declined = respond(agent, JsonPath.read(first, "$.transactionReference"), false);
        assertThat((String) JsonPath.read(declined, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(declined, "$.failureReason")).contains("declined");
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo("14000");
        collect(agent, contractId, "100", phone, UUID.randomUUID().toString());
    }

    @Test
    void retryWithTheSameKeyDoesNotSendASecondPrompt() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String phone = randomPhone();
        Long contractId = approveContract(createCustomer(admin, phone), admin, createDevice(admin));
        String agent = loginAs("amina.agent@tracom.local", "Agent@1234");
        String key = UUID.randomUUID().toString();
        String a = collect(agent, contractId, "70", phone, key);
        String b = collect(agent, contractId, "70", phone, key);
        assertThat((String) JsonPath.read(b, "$.transactionReference"))
                .isEqualTo(JsonPath.read(a, "$.transactionReference"));
    }

    @Test
    void onlyTheAssignedAgentCanOperateAnActiveTerminal() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String brian = loginAs("brian.agent@tracom.local", "Agent@1234");
        String amina = loginAs("amina.agent@tracom.local", "Agent@1234");
        String customer = createCustomer(admin, randomPhone());

        mockMvc.perform(get("/api/v1/pos/{t}/session", TERMINAL).header("Authorization", "Bearer " + brian))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/pos/{t}/session", TERMINAL).header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden());

        // A new terminal at QuickMart, given to Amina, then suspended.
        String serial = "TST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult created = mockMvc.perform(post("/api/v1/admin/terminals")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\": \"%s\", \"model\": \"Ingenico Move/5000\", \"merchantId\": %d}"
                                .formatted(serial, merchantId(admin, "QuickMart Electronics"))))
                .andExpect(status().isCreated())
                .andReturn();
        Long terminalId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        String code = JsonPath.read(created.getResponse().getContentAsString(), "$.terminalCode");
        assertThat(code).startsWith("TRM-");

        // Faith works for TechPoint, so she cannot be given a QuickMart terminal.
        mockMvc.perform(post("/api/v1/admin/terminals/{id}/assign", terminalId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\": %d}".formatted(agentId(admin, "AGT-00003"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/terminals/{id}/assign", terminalId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\": %d}".formatted(agentId(admin, "AGT-00001"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/pos/{t}/session", code).header("Authorization", "Bearer " + amina))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/terminals/{id}/status", terminalId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SUSPENDED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/pos/{t}/session", code).header("Authorization", "Bearer " + amina))
                .andExpect(status().isForbidden());

        // The console shows the fleet; the agent sees their own terminals.
        String fleet = getJson(admin, "/api/v1/admin/terminals");
        assertThat(JsonPath.<List<String>>read(fleet, "$[*].terminalCode")).contains(TERMINAL, code);
        assertThat(JsonPath.<List<String>>read(getJson(amina, "/api/v1/agents/me/terminals"), "$[*].terminalCode"))
                .contains(TERMINAL);
        mockMvc.perform(post("/api/v1/admin/terminals").header("Authorization", "Bearer " + amina)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\": \"NOPE-1\", \"model\": \"X\", \"merchantId\": 1}"))
                .andExpect(status().isForbidden());
    }

    private String collect(String agent, Long contractId, String amount, String phone, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pos/{t}/collections", TERMINAL)
                        .header("Authorization", "Bearer " + agent)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collectBody(contractId, amount, phone)))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static String collectBody(Long contractId, String amount, String phone) {
        if (phone == null) {
            return "{\"contractId\": %d, \"amount\": %s}".formatted(contractId, amount);
        }
        return "{\"contractId\": %d, \"amount\": %s, \"payerMsisdn\": \"%s\"}".formatted(contractId, amount, phone);
    }

    private String respond(String agent, String reference, boolean approved) throws Exception {
        return mockMvc.perform(post("/api/v1/pos/{t}/collections/{r}/customer-response", TERMINAL, reference)
                        .header("Authorization", "Bearer " + agent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\": %s}".formatted(approved)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String getJson(String token, String path) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String randomPhone() {
        return "07" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private String createCustomer(String admin, String phone) throws Exception {
        String email = "terminal-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Terminal Customer", "email": "%s", "phoneNumber": "%s",
                                 "password": "Secret123", "role": "CUSTOMER"}
                                """.formatted(email, phone)))
                .andExpect(status().isCreated());
        return loginAs(email, "Secret123");
    }

    private Long merchantId(String admin, String name) throws Exception {
        List<Integer> ids = JsonPath.read(getJson(admin, "/api/v1/admin/merchants"),
                "$[?(@.businessName == '" + name + "')].id");
        return ids.get(0).longValue();
    }

    private Long agentId(String admin, String code) throws Exception {
        List<Integer> ids = JsonPath.read(getJson(admin, "/api/v1/admin/agents"), "$[?(@.agentCode == '" + code + "')].id");
        return ids.get(0).longValue();
    }

    private String loginAs(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private Long createDevice(String adminToken) throws Exception {
        Long productId = productRepository.findByName("Samsung Galaxy A15").orElseThrow().getId();
        MvcResult created = mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId,
                                        String.format("356%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "PS-" + UUID.randomUUID().toString().replace("-", "")
                                                .substring(0, 10).toUpperCase())))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long approveContract(String customerToken, String officerToken, Long deviceId) throws Exception {
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
}
