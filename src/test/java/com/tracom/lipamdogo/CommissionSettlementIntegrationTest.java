package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import com.tracom.lipamdogo.model.CommissionEntry;
import com.tracom.lipamdogo.model.CommissionStatus;
import com.tracom.lipamdogo.model.CommissionType;
import com.tracom.lipamdogo.model.Device;
import com.tracom.lipamdogo.repository.CommissionEntryRepository;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.MerchantRepository;
import com.tracom.lipamdogo.repository.PaymentPlanRepository;
import com.tracom.lipamdogo.repository.PaymentTransactionRepository;
import com.tracom.lipamdogo.repository.ProductRepository;
import com.tracom.lipamdogo.support.SystemTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Commissions (accrue on posted terminal collections, claw back on reversal,
 * flat sale fee) and settlement (maker-checker M-PESA payouts).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommissionSettlementIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(950000);
    private static final String TERMINAL = "TRM-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PaymentPlanRepository paymentPlanRepository;

    @Autowired
    private LoanContractRepository loanContractRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private CommissionEntryRepository entryRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Test
    void terminalCollectionsEarnCommissionAppPaymentsDoNot() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String phone = randomPhone();
        String customer = createCustomer(admin, phone);
        Long contractId = approveContract(customer, admin, createDevice(admin));
        String agent = loginAs("amina.agent@tracom.local", "Agent@1234");

        String ref = collectAndApprove(agent, contractId, "1000", phone);
        List<CommissionEntry> entries = entriesFor(ref);
        assertThat(entries).hasSize(2);
        assertThat(amountOf(entries, CommissionType.AGENT_COLLECTION)).isEqualByComparingTo("20.00");
        assertThat(amountOf(entries, CommissionType.MERCHANT_COLLECTION)).isEqualByComparingTo("5.00");
        assertThat(entries).allMatch(e -> e.getStatus() == CommissionStatus.ACCRUED);

        // The agent's share is capped per payment (2% of 5,000 would be 100).
        String big = collectAndApprove(agent, contractId, "5000", phone);
        assertThat(amountOf(entriesFor(big), CommissionType.AGENT_COLLECTION)).isEqualByComparingTo("50.00");

        // The customer paying from their own app earns nobody commission.
        MvcResult own = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": 70, \"channel\": \"MPESA\", \"mockScenario\": \"SUCCESS\"}"
                                .formatted(contractId)))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(entriesFor(JsonPath.read(own.getResponse().getContentAsString(), "$.transactionReference"))).isEmpty();

        // The terminal's end-of-day report shows what the agent earned today.
        String summary = getJson(agent, "/api/v1/pos/" + TERMINAL + "/day-summary");
        assertThat(JsonPath.<Number>read(summary, "$.commissionEarnedToday").doubleValue()).isGreaterThanOrEqualTo(70.0);
    }

    @Test
    void reversingAPaymentClawsItsCommissionBack() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String phone = randomPhone();
        Long contractId = approveContract(createCustomer(admin, phone), admin, createDevice(admin));
        String agent = loginAs("amina.agent@tracom.local", "Agent@1234");
        String ref = collectAndApprove(agent, contractId, "1000", phone);

        mockMvc.perform(post("/api/v1/payments/{ref}/reverse", ref)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Wrong contract\"}"))
                .andExpect(status().isOk());

        List<CommissionEntry> entries = entriesFor(ref);
        assertThat(entries).hasSize(4);
        BigDecimal net = entries.stream().map(CommissionEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("0");
        assertThat(entries.stream().filter(e -> e.getAmount().signum() < 0))
                .allMatch(e -> e.getReversesEntry() != null);
    }

    @Test
    void merchantEarnsAFlatFeeForAPhoneFinancedFromItsStock() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        Long deviceId = createDevice(admin);
        Device device = deviceRepository.findById(deviceId).orElseThrow();
        device.setMerchant(merchantRepository.findAll().stream()
                .filter(m -> m.getBusinessName().equals("QuickMart Electronics")).findFirst().orElseThrow());
        deviceRepository.save(device);

        Long contractId = approveContract(createCustomer(admin, randomPhone()), admin, deviceId);
        assertThat(entryRepository.existsByContractIdAndCommissionType(contractId, CommissionType.MERCHANT_SALE)).isTrue();
        assertThat(entryRepository.findAll().stream()
                .filter(e -> e.getContract() != null && e.getContract().getId().equals(contractId))
                .findFirst().orElseThrow().getAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void settlementNeedsASecondPersonAndPaysOutOverMpesa() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String operator = createStaff(admin, "PAYMENTS_OPERATOR");
        String phone = randomPhone();
        Long contractId = approveContract(createCustomer(admin, phone), admin, createDevice(admin));
        String agent = loginAs("amina.agent@tracom.local", "Agent@1234");
        String ref = collectAndApprove(agent, contractId, "500", phone);

        // Maker prepares the run.
        String batch = runSettlement(admin, 201);
        Long batchId = ((Number) JsonPath.read(batch, "$.id")).longValue();
        assertThat((String) JsonPath.read(batch, "$.status")).isEqualTo("PENDING_APPROVAL");
        assertThat(JsonPath.<List<String>>read(batch, "$.lines[?(@.payeeName == 'Amina Yusuf')].payoutMethod"))
                .containsOnly("MPESA_B2C");
        assertThat(JsonPath.<List<String>>read(batch, "$.lines[?(@.payeeName == 'QuickMart Electronics')].payoutMethod"))
                .containsOnly("MPESA_B2B");
        assertThat(entriesFor(ref)).allMatch(e -> e.getStatus() == CommissionStatus.IN_SETTLEMENT);

        // The same commission cannot be put in a second batch.
        runSettlement(admin, 400);

        // The maker cannot approve their own batch.
        mockMvc.perform(post("/api/v1/admin/settlements/{id}/approve", batchId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isForbidden());

        // A different person approves: payouts go out over M-PESA.
        MvcResult approved = mockMvc.perform(post("/api/v1/admin/settlements/{id}/approve", batchId)
                        .header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"Checked against the ledger\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = approved.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("PAID");
        assertThat(JsonPath.<List<String>>read(body, "$.lines[?(@.payeeName == 'Amina Yusuf')].payoutReference").get(0))
                .startsWith("MPESA-B2C-");
        assertThat(entriesFor(ref)).allMatch(e -> e.getStatus() == CommissionStatus.SETTLED);

        String mine = getJson(agent, "/api/v1/agents/me/commissions");
        assertThat(JsonPath.<Number>read(mine, "$.paidToDate").doubleValue()).isGreaterThanOrEqualTo(10.0);
        assertThat(JsonPath.<List<?>>read(mine, "$.payouts")).isNotEmpty();
    }

    @Test
    void rejectedBatchReturnsCommissionToTheNextRun() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String operator = createStaff(admin, "PAYMENTS_OPERATOR");
        String phone = randomPhone();
        Long contractId = approveContract(createCustomer(admin, phone), admin, createDevice(admin));
        String ref = collectAndApprove(loginAs("amina.agent@tracom.local", "Agent@1234"), contractId, "800", phone);

        Long batchId = ((Number) JsonPath.read(runSettlement(operator, 201), "$.id")).longValue();
        mockMvc.perform(post("/api/v1/admin/settlements/{id}/reject", batchId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"Wait for month end\"}"))
                .andExpect(status().isOk());
        assertThat(entriesFor(ref)).allMatch(e -> e.getStatus() == CommissionStatus.ACCRUED && e.getSettlementLine() == null);
        mockMvc.perform(post("/api/v1/admin/settlements/{id}/approve", batchId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void partnersCannotRunSettlement() throws Exception {
        String agent = loginAs("amina.agent@tracom.local", "Agent@1234");
        mockMvc.perform(get("/api/v1/admin/settlements").header("Authorization", "Bearer " + agent))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/settlements").header("Authorization", "Bearer " + agent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cutoffDate\": \"%s\"}".formatted(SystemTime.today())))
                .andExpect(status().isForbidden());
    }

    private String runSettlement(String token, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/settlements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cutoffDate\": \"%s\"}".formatted(SystemTime.today())))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private String collectAndApprove(String agent, Long contractId, String amount, String phone) throws Exception {
        String pending = collect(agent, contractId, amount, phone, UUID.randomUUID().toString());
        String ref = JsonPath.read(pending, "$.transactionReference");
        respond(agent, ref, true);
        return ref;
    }

    private List<CommissionEntry> entriesFor(String reference) {
        Long txId = transactionRepository.findByTransactionReference(reference).orElseThrow().getId();
        return entryRepository.findAllByPaymentTransactionId(txId).stream()
                .sorted(Comparator.comparing(CommissionEntry::getId)).toList();
    }

    private static BigDecimal amountOf(List<CommissionEntry> entries, CommissionType type) {
        return entries.stream().filter(e -> e.getCommissionType() == type)
                .map(CommissionEntry::getAmount).findFirst().orElseThrow();
    }

    private String createStaff(String admin, String role) throws Exception {
        String email = "ops-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Second Approver", "email": "%s",
                                 "password": "Secret123", "role": "%s"}
                                """.formatted(email, role)))
                .andExpect(status().isCreated());
        return loginAs(email, "Secret123");
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
        String email = "commission-" + UUID.randomUUID() + "@example.com";
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
                                        String.format("357%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "CM-" + UUID.randomUUID().toString().replace("-", "")
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
