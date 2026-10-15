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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5A reconciliation: matching, mismatches, missing sides, duplicates,
 * idempotent import/runs, RBAC, and the no-financial-side-effects rule.
 * Assertions always filter by this test's own references: the in-memory DB
 * is shared across test classes in one JVM run.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(950000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.tracom.lipamdogo.repository.ProductRepository productRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.PaymentPlanRepository paymentPlanRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.LoanContractRepository loanContractRepository;

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String uniqueRef(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
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

    private String createStaff(String adminToken, String name, String role) throws Exception {
        String email = uniqueEmail(name.replaceAll("\\s+", "").toLowerCase());
        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "%s", "email": "%s",
                                 "password": "Secret123", "role": "%s"}
                                """.formatted(name, email, role)))
                .andExpect(status().isCreated());
        return loginAs(email, "Secret123");
    }

    private Long createDevice(String adminToken) throws Exception {
        Long productId = productRepository.findAll().get(0).getId();
        MvcResult created = mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId,
                                        String.format("359%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "RC-" + UUID.randomUUID().toString().replace("-", "")
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

    private String initiateExpect(String token, Long contractId, String amount, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": %s, \"channel\": \"MPESA\"}".formatted(contractId, amount)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }

    private String feedJson(List<Map<String, Object>> items) throws Exception {
        StringBuilder sb = new StringBuilder("{\"transactions\":[");
        for (int i = 0; i < items.size(); i++) {
            var m = items.get(i);
            sb.append("{\"provider\":\"").append(m.get("provider"))
                    .append("\",\"providerReference\":\"").append(m.get("ref"))
                    .append("\",\"amount\":").append(m.get("amount"))
                    .append(",\"transactionTime\":\"").append(m.get("time"))
                    .append("\",\"status\":\"").append(m.get("status")).append("\"}");
            if (i < items.size() - 1) {
                sb.append(",");
            }
        }
        return sb.append("]}").toString();
    }

    private Map<String, Object> feedRow(String ref, Object amount, String status) {
        return Map.of("provider", "MPESA", "ref", ref, "amount", amount,
                "time", "2026-09-21T17:02:00", "status", status);
    }

    private MvcResult importFeed(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/reconciliation/feed")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult runRecon(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/reconciliation/runs")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recordsByRef(String token, String ref) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/reconciliation/records")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> all = JsonPath.read(result.getResponse().getContentAsString(), "$");
        return all.stream()
                .filter(r -> ref.equals(r.get("providerReference")) || ref.equals(r.get("internalReference")))
                .toList();
    }

    @Test
    void matchedTransaction() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Recon Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Recon Matched", uniqueEmail("recon-matched"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String body = initiateExpect(customerToken, contractId, "500", 201);
        String providerRef = JsonPath.read(body, "$.providerReference");
        var before = loanContractRepository.findById(contractId).orElseThrow();

        MvcResult imported = importFeed(adminToken, feedJson(List.of(feedRow(providerRef, "500", "SUCCESS"))));
        assertThat(imported.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(imported.getResponse().getContentAsString(), "$.inserted")).isEqualTo(1);

        MvcResult run = runRecon(adminToken);
        assertThat(run.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(run.getResponse().getContentAsString(), "$.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(run.getResponse().getContentAsString(), "$.batchNumber")).startsWith("RECON-");

        var matches = recordsByRef(adminToken, providerRef);
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).get("status")).isEqualTo("MATCHED");

        // Reconciliation never moves money or states.
        var after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountPaid()).isEqualByComparingTo(before.getAmountPaid());
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(before.getAmountRemaining());
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
    }

    @Test
    void amountMismatch() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Recon Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Recon Amount", uniqueEmail("recon-amount"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String body = initiateExpect(customerToken, contractId, "500", 201);
        String providerRef = JsonPath.read(body, "$.providerReference");

        importFeed(adminToken, feedJson(List.of(feedRow(providerRef, "450", "SUCCESS"))));
        runRecon(adminToken);

        var matches = recordsByRef(adminToken, providerRef);
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).get("status")).isEqualTo("AMOUNT_MISMATCH");
        assertThat(new java.math.BigDecimal(matches.get(0).get("differenceAmount").toString()))
                .isEqualByComparingTo(new java.math.BigDecimal("-50"));

        var after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountPaid()).isEqualByComparingTo(new java.math.BigDecimal("500"));
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(new java.math.BigDecimal("13500"));
    }

    @Test
    void statusMismatch() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Recon Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Recon Status", uniqueEmail("recon-status"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        // Internal FAILED, provider SUCCESS.
        MvcResult failed = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": 500, \"channel\": \"MPESA\", \"mockScenario\": \"FAILED\"}"
                                .formatted(contractId)))
                .andReturn();
        assertThat(failed.getResponse().getStatus()).isEqualTo(201);
        String failedRef = JsonPath.read(failed.getResponse().getContentAsString(), "$.providerReference");

        // Internal POSTED, provider FAILED.
        String postedBody = initiateExpect(customerToken, contractId, "300", 201);
        String postedRef = JsonPath.read(postedBody, "$.providerReference");

        importFeed(adminToken, feedJson(List.of(
                feedRow(failedRef, "500", "SUCCESS"),
                feedRow(postedRef, "300", "FAILED"))));
        runRecon(adminToken);

        var first = recordsByRef(adminToken, failedRef);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).get("status")).isEqualTo("STATUS_MISMATCH");

        var second = recordsByRef(adminToken, postedRef);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).get("status")).isEqualTo("STATUS_MISMATCH");

        // Nothing was auto-corrected: FAILED stayed FAILED, POSTED stayed POSTED.
        var after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountPaid()).isEqualByComparingTo(new java.math.BigDecimal("300"));
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(new java.math.BigDecimal("13700"));
    }

    @Test
    void missingInternal() throws Exception {
        String adminToken = loginAsAdmin();
        String ghostRef = uniqueRef("MPESA-999");
        long txCountBefore = countAllTransactions();

        importFeed(adminToken, feedJson(List.of(feedRow(ghostRef, "500", "SUCCESS"))));
        runRecon(adminToken);

        var matches = recordsByRef(adminToken, ghostRef);
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).get("status")).isEqualTo("MISSING_INTERNAL");
        assertThat(matches.get(0).get("internalReference")).isNull();

        // Provider evidence never becomes an internal payment on its own.
        assertThat(countAllTransactions()).isEqualTo(txCountBefore);
    }

    private long countAllTransactions() throws Exception {
        String adminToken = loginAsAdmin();
        MvcResult result = mockMvc.perform(get("/api/v1/admin/payments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        List<?> all = JsonPath.read(result.getResponse().getContentAsString(), "$");
        return all.size();
    }

    @Test
    void missingProvider() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Recon Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Recon Missing", uniqueEmail("recon-missing"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String body = initiateExpect(customerToken, contractId, "700", 201);
        String internalRef = JsonPath.read(body, "$.transactionReference");
        var before = loanContractRepository.findById(contractId).orElseThrow();

        // Full-scope run with a feed that omits this transaction: the engine
        // must flag it without touching the customer's money.
        MvcResult run = runRecon(adminToken);
        assertThat(run.getResponse().getStatus()).isEqualTo(200);

        // MISSING_PROVIDER records carry no provider side: match by internal ref.
        MvcResult all = mockMvc.perform(get("/api/v1/admin/reconciliation/records")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> every = JsonPath.read(all.getResponse().getContentAsString(), "$");
        var missing = every.stream()
                .filter(r -> internalRef.equals(r.get("internalReference"))
                        && "MISSING_PROVIDER".equals(r.get("status")))
                .toList();
        assertThat(missing).hasSize(1);

        // No automatic reversal: the customer's payment stands.
        var after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountPaid()).isEqualByComparingTo(before.getAmountPaid());
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(before.getAmountRemaining());
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
    }

    @Test
    void duplicateProviderReferenceDetected() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Recon Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Recon Dupe", uniqueEmail("recon-dupe"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String body = initiateExpect(customerToken, contractId, "500", 201);
        String providerRef = JsonPath.read(body, "$.providerReference");

        // Same reference twice with differing amounts: both stored, surplus flagged.
        String dupFeed = "{\"transactions\":["
                + "{\"provider\":\"MPESA\",\"providerReference\":\"" + providerRef + "\","
                + "\"amount\":500,\"transactionTime\":\"2026-09-21T17:02:00\",\"status\":\"SUCCESS\"},"
                + "{\"provider\":\"MPESA\",\"providerReference\":\"" + providerRef + "\","
                + "\"amount\":450,\"transactionTime\":\"2026-09-21T17:02:00\",\"status\":\"SUCCESS\"}]}";
        MvcResult imported = importFeed(adminToken, dupFeed);
        assertThat(imported.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(imported.getResponse().getContentAsString(), "$.inserted")).isEqualTo(2);

        runRecon(adminToken);

        var matches = recordsByRef(adminToken, providerRef);
        var statuses = matches.stream().map(r -> (String) r.get("status")).toList();
        assertThat(statuses).contains("MATCHED", "DUPLICATE_PROVIDER");

        // Exactly one internal payment exists; nothing posted twice.
        var after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountPaid()).isEqualByComparingTo(new java.math.BigDecimal("500"));
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(new java.math.BigDecimal("13500"));
    }

    @Test
    void repeatImportAndRerunAreIdempotent() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Recon Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Recon Idem", uniqueEmail("recon-idem"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String body = initiateExpect(customerToken, contractId, "500", 201);
        String providerRef = JsonPath.read(body, "$.providerReference");
        String feed = feedJson(List.of(feedRow(providerRef, "500", "SUCCESS")));

        MvcResult first = importFeed(adminToken, feed);
        assertThat((Integer) JsonPath.read(first.getResponse().getContentAsString(), "$.inserted")).isEqualTo(1);
        MvcResult run1 = runRecon(adminToken);
        long total1 = ((Number) JsonPath.read(run1.getResponse().getContentAsString(), "$.totalRecords")).longValue();

        MvcResult second = importFeed(adminToken, feed);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(second.getResponse().getContentAsString(), "$.inserted")).isEqualTo(0);
        assertThat((Integer) JsonPath.read(second.getResponse().getContentAsString(), "$.skippedDuplicates")).isEqualTo(1);

        MvcResult run2 = runRecon(adminToken);
        long total2 = ((Number) JsonPath.read(run2.getResponse().getContentAsString(), "$.totalRecords")).longValue();
        assertThat(total2).isEqualTo(total1);

        var matches = recordsByRef(adminToken, providerRef);
        assertThat(matches.stream().filter(r -> "MATCHED".equals(r.get("status"))).count()).isEqualTo(1);
    }

    @Test
    void rbacDeniedForNonStaff() throws Exception {
        String adminToken = loginAsAdmin();
        String customerToken = registerAndLogin("Recon Rbac", uniqueEmail("recon-rbac"));
        String agentToken = createStaff(adminToken, "Recon Agent", "AGENT");
        String merchantToken = createStaff(adminToken, "Recon Merchant", "MERCHANT");
        String feed = feedJson(List.of(feedRow(uniqueRef("MPESA-RBAC"), "100", "SUCCESS")));

        for (String token : List.of(customerToken, agentToken, merchantToken)) {
            mockMvc.perform(post("/api/v1/admin/reconciliation/feed")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feed))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/admin/reconciliation/runs")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/admin/reconciliation/batches")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/admin/reconciliation/records")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        String operatorToken = createStaff(adminToken, "Recon Operator", "PAYMENTS_OPERATOR");
        mockMvc.perform(post("/api/v1/admin/reconciliation/feed")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feed))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/reconciliation/runs")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/reconciliation/batches")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk());
    }

    @Test
    void providerReferenceMapsDeterministically() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Recon Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Recon Deterministic", uniqueEmail("recon-determ"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String body = initiateExpect(customerToken, contractId, "500", 201);
        String providerRef = JsonPath.read(body, "$.providerReference");
        String internalRef = JsonPath.read(body, "$.transactionReference");
        importFeed(adminToken, feedJson(List.of(feedRow(providerRef, "500", "SUCCESS"))));

        runRecon(adminToken);
        runRecon(adminToken);

        var matches = recordsByRef(adminToken, providerRef);
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).get("internalReference")).isEqualTo(internalRef);

        // Matched run never posts again.
        var after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountPaid()).isEqualByComparingTo(new java.math.BigDecimal("500"));
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(new java.math.BigDecimal("13500"));
    }

    @Test
    void batchesListAndFilter() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Recon Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Recon Batches", uniqueEmail("recon-batches"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String body = initiateExpect(customerToken, contractId, "500", 201);
        String providerRef = JsonPath.read(body, "$.providerReference");
        importFeed(adminToken, feedJson(List.of(feedRow(providerRef, "500", "SUCCESS"))));
        MvcResult run = runRecon(adminToken);
        Long batchId = ((Number) JsonPath.read(run.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(get("/api/v1/admin/reconciliation/batches")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/admin/reconciliation/batches/{id}", batchId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNumber").isNotEmpty())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(get("/api/v1/admin/reconciliation/records")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("batchId", String.valueOf(batchId))
                        .param("status", "MATCHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }
}
