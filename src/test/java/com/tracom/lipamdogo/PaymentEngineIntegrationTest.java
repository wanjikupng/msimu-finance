package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import com.tracom.lipamdogo.dto.PaymentInitiateRequest;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.payment.MockProviderScenario;
import com.tracom.lipamdogo.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 payment engine: lifecycle, posting exactly once, allocation,
 * idempotency, reversals, overdue/paid-off rules, invariants, and concurrency.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentEngineIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(800000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.tracom.lipamdogo.repository.ProductRepository productRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.PaymentPlanRepository paymentPlanRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.UserAccountRepository userAccountRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.LoanContractRepository loanContractRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.PaymentTransactionRepository transactionRepository;

    @Autowired
    private com.tracom.lipamdogo.service.PaymentService paymentService;

    @Autowired
    private com.tracom.lipamdogo.service.PaymentPostingService postingService;

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
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
                                        String.format("352%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "PE-" + UUID.randomUUID().toString().replace("-", "")
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

    private MvcResult initiate(String token, Long contractId, String amount, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String initiateExpect(String token, Long contractId, String amount, int expectedStatus) throws Exception {
        MvcResult result = initiate(token, contractId, amount,
                "{\"contractId\": %d, \"amount\": %s, \"channel\": \"MPESA\"}".formatted(contractId, amount));
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }

    private BigDecimal remainingOf(Long contractId) {
        return loanContractRepository.findById(contractId).orElseThrow().getAmountRemaining();
    }

    private UserPrincipal principalFor(String email) {
        return new UserPrincipal(userAccountRepository.findByEmail(email).orElseThrow());
    }

    @Test
    void successfulPaymentPostsImmediatelyWithReceipt() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Pay Customer", uniqueEmail("pay"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        MvcResult result = initiate(customerToken, contractId, "500",
                "{\"contractId\": %d, \"amount\": 500, \"channel\": \"MPESA\"}".formatted(contractId));
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(body, "$.transactionReference")).startsWith("PAY-");
        assertThat((String) JsonPath.read(body, "$.providerReference")).startsWith("MPESA-MOCK-");
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("POSTED");
        assertThat((String) JsonPath.read(body, "$.receiptNumber")).startsWith("RCPT-");

        // Receipt carries the full business context, no internal ids.
        String reference = JsonPath.read(body, "$.transactionReference");
        mockMvc.perform(get("/api/v1/payments/{reference}/receipt", reference)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(500.0))
                .andExpect(jsonPath("$.channel").value("MPESA"))
                .andExpect(jsonPath("$.remainingBalance").value(13500.0))
                .andExpect(jsonPath("$.contractNumber").isNotEmpty())
                .andExpect(jsonPath("$.deviceCode").isNotEmpty());

        assertThat(remainingOf(contractId)).isEqualByComparingTo(new BigDecimal("13500"));
    }

    @Test
    void pendingAndFailedPaymentsHaveZeroFinancialEffect() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Pending Customer", uniqueEmail("pending"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        MvcResult pending = initiate(customerToken, contractId, "500",
                "{\"contractId\": %d, \"amount\": 500, \"channel\": \"MPESA\", \"mockScenario\": \"PENDING\"}"
                        .formatted(contractId));
        assertThat(pending.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(pending.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("PENDING");
        assertThat(remainingOf(contractId)).isEqualByComparingTo(new BigDecimal("14000"));

        MvcResult failed = initiate(customerToken, contractId, "500",
                "{\"contractId\": %d, \"amount\": 500, \"channel\": \"MPESA\", \"mockScenario\": \"FAILED\"}"
                        .formatted(contractId));
        String failedBody = failed.getResponse().getContentAsString();
        assertThat(failed.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(failedBody, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failedBody, "$.failureReason")).isNotBlank();
        assertThat(remainingOf(contractId)).isEqualByComparingTo(new BigDecimal("14000"));
    }

    @Test
    void successUnpostedHasZeroEffectUntilExplicitPosting() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Pay Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Deferred Customer", uniqueEmail("deferred"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        MvcResult referenceSource = initiate(customerToken, contractId, "500",
                "{\"contractId\": %d, \"amount\": 500, \"channel\": \"MPESA\", \"mockScenario\": \"PENDING\"}"
                        .formatted(contractId));
        String reference = JsonPath.read(referenceSource.getResponse().getContentAsString(), "$.transactionReference");

        // Provider confirms later: SUCCESS but still unposted → no effect.
        mockMvc.perform(post("/api/v1/payments/{reference}/provider-result", reference)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(remainingOf(contractId)).isEqualByComparingTo(new BigDecimal("14000"));

        // Repeated callbacks are safe no-ops.
        mockMvc.perform(post("/api/v1/payments/{reference}/provider-result", reference)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
        assertThat(remainingOf(contractId)).isEqualByComparingTo(new BigDecimal("14000"));

        // Explicit posting applies exactly once; second post is a no-op.
        mockMvc.perform(post("/api/v1/payments/{reference}/post", reference)
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingBalance").value(13500.0));
        mockMvc.perform(post("/api/v1/payments/{reference}/post", reference)
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingBalance").value(13500.0));
        assertThat(remainingOf(contractId)).isEqualByComparingTo(new BigDecimal("13500"));

        // Customers can never post or simulate provider results.
        mockMvc.perform(post("/api/v1/payments/{reference}/post", reference)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/payments/{reference}/provider-result", reference)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"SUCCESS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void partialPaymentsAccumulateAndRollInstallments() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Partial Customer", uniqueEmail("partial"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        LoanContract before = loanContractRepository.findById(contractId).orElseThrow();
        LocalDate dueDate = before.getNextPaymentDueDate();

        // 60 of 70 due: credited, due date unchanged, still ACTIVE.
        initiateExpect(customerToken, contractId, "60", 201);
        LoanContract afterFirst = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(afterFirst.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("13940"));
        assertThat(afterFirst.getInstallmentPaid()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(afterFirst.getNextPaymentDueDate()).isEqualTo(dueDate);
        assertThat(afterFirst.getStatus().name()).isEqualTo("ACTIVE");

        // 250 more: 60 carried + 250 = 310 = four full installments (280)
        // plus 30 toward the fifth: due date advances four days.
        initiateExpect(customerToken, contractId, "250", 201);
        LoanContract afterSecond = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(afterSecond.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("13690"));
        assertThat(afterSecond.getInstallmentPaid()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(afterSecond.getNextPaymentDueDate()).isEqualTo(dueDate.plusDays(4));
    }

    @Test
    void multiplePaymentsSatisfyInvariant() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Multi Pay", uniqueEmail("multipay"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        initiateExpect(customerToken, contractId, "100", 201);
        initiateExpect(customerToken, contractId, "200", 201);
        initiateExpect(customerToken, contractId, "300", 201);

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountPaid()).isEqualByComparingTo(new BigDecimal("600"));
        // Invariant 1: paid + outstanding = original total payable.
        assertThat(contract.getAmountPaid().add(contract.getAmountRemaining()))
                .isEqualByComparingTo(contract.getTotalPayable());
    }

    @Test
    void fullPaymentClosesContractAndDevice() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Final Customer", uniqueEmail("final"));
        Long deviceId = createDevice(adminToken);
        Long contractId = approveContractFor(customerToken, officerToken, deviceId);

        initiateExpect(customerToken, contractId, "14000", 201);

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isZero();
        assertThat(contract.getStatus().name()).isEqualTo("PAID_OFF");
        assertThat(contract.getAmountPaid().add(contract.getAmountRemaining()))
                .isEqualByComparingTo(contract.getTotalPayable());

        var device = autowiredDevice(deviceId);
        assertThat(device.getStatus().name()).isEqualTo("PAID_OFF");

        // Paid-off contracts accept no further payments.
        initiateExpect(customerToken, contractId, "100", 400);
    }

    @Autowired
    private com.tracom.lipamdogo.repository.DeviceRepository deviceRepository;

    private com.tracom.lipamdogo.model.Device autowiredDevice(Long deviceId) {
        return deviceRepository.findById(deviceId).orElseThrow();
    }

    @Test
    void initiationValidatesBoundaries() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Boundary Customer", uniqueEmail("boundary"));
        String otherToken = registerAndLogin("Boundary Other", uniqueEmail("boundary-other"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        // Zero amount.
        initiateExpect(customerToken, contractId, "0", 400);
        // Overpayment beyond outstanding.
        initiateExpect(customerToken, contractId, "999999", 400);
        // Unsupported channel (no adapter in this phase).
        MvcResult card = initiate(customerToken, contractId, "100",
                "{\"contractId\": %d, \"amount\": 100, \"channel\": \"CARD\"}".formatted(contractId));
        assertThat(card.getResponse().getStatus()).isEqualTo(400);
        // Missing contract.
        MvcResult missing = initiate(customerToken, contractId, "100",
                "{\"contractId\": 999999999, \"amount\": 100, \"channel\": \"MPESA\"}");
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        // Another customer's contract.
        MvcResult foreign = initiate(otherToken, contractId, "100",
                "{\"contractId\": %d, \"amount\": 100, \"channel\": \"MPESA\"}".formatted(contractId));
        assertThat(foreign.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void idempotencyKeyPreventsDoubleCharge() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Idempotent Customer", uniqueEmail("idempotent"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        String key = "idem-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": 500, \"channel\": \"MPESA\"}".formatted(contractId)))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": 500, \"channel\": \"MPESA\"}".formatted(contractId)))
                .andReturn();

        String firstRef = JsonPath.read(first.getResponse().getContentAsString(), "$.transactionReference");
        String secondRef = JsonPath.read(second.getResponse().getContentAsString(), "$.transactionReference");
        assertThat(secondRef).isEqualTo(firstRef);
        assertThat(transactionRepository.findAllByContractId(contractId)).hasSize(1);
        assertThat(remainingOf(contractId)).isEqualByComparingTo(new BigDecimal("13500"));

        // Same key, different intent → rejected, not silently applied.
        MvcResult mismatch = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": 900, \"channel\": \"MPESA\"}".formatted(contractId)))
                .andReturn();
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void reversalRollsBackFinancialEffect() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Pay Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Reversed Customer", uniqueEmail("reversed"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String body = initiateExpect(customerToken, contractId, "1000", 201);
        String reference = JsonPath.read(body, "$.transactionReference");
        assertThat(remainingOf(contractId)).isEqualByComparingTo(new BigDecimal("13000"));

        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Duplicate counter entry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(contract.getAmountPaid()).isZero();
        assertThat(contract.getAmountPaid().add(contract.getAmountRemaining()))
                .isEqualByComparingTo(contract.getTotalPayable());

        // Reversing again is rejected; customers cannot reverse at all.
        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"again\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"customer attempt\"}"))
                .andExpect(status().isForbidden());

        // Financial history is never deleted.
        mockMvc.perform(delete("/api/v1/payments/{reference}", reference)
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void overdueRuleAndDashboardSummary() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Overdue Customer", uniqueEmail("overdue"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        // Simulate time passing beyond the due date.
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        contract.setNextPaymentDueDate(LocalDate.now().minusDays(2));
        loanContractRepository.save(contract);

        // Any new initiation re-evaluates state: past due + unpaid → OVERDUE.
        initiateExpect(customerToken, contractId, "60", 201);
        assertThat(loanContractRepository.findById(contractId).orElseThrow().getStatus().name())
                .isEqualTo("OVERDUE");

        // Dashboard reports server-computed arrears, expectations, timeline.
        MvcResult dashboard = mockMvc.perform(get("/api/v1/users/me/dashboard")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentSummary.totalFinanced").value(14000.0))
                .andExpect(jsonPath("$.paymentSummary.paidDevices").value(60.0))
                .andExpect(jsonPath("$.paymentSummary.outstandingDevices").value(13940.0))
                .andExpect(jsonPath("$.paymentSummary.arrears").value(10.0))
                .andExpect(jsonPath("$.paymentSummary.todayExpected").value(70.0))
                .andExpect(jsonPath("$.paymentSummary.currentStatus").value("OVERDUE"))
                .andExpect(jsonPath("$.paymentTimeline.length()").value(1))
                .andExpect(jsonPath("$.paymentTimeline[0].status").value("POSTED"))
                .andReturn();
        assertThat(dashboard.getResponse().getContentAsString()).doesNotContain("password");
    }

    @Test
    void staffTransactionViewIsRestricted() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Pay Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Pay Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Listed Customer", uniqueEmail("listed"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        initiateExpect(customerToken, contractId, "100", 201);

        mockMvc.perform(get("/api/v1/admin/payments")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/admin/payments?status=POSTED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/payments")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        // Customer sees only their own transactions.
        mockMvc.perform(get("/api/v1/payments/me")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void concurrentPostOfSameTransactionAppliesOnce() throws Exception {
        // Service-level actors (no MockMvc across threads).
        String customerEmail = uniqueEmail("conc-customer");
        registerCustomerDirect(customerEmail);
        UserPrincipal customer = principalFor(customerEmail);

        com.tracom.lipamdogo.model.Product product = productRepository.findAll().get(0);
        com.tracom.lipamdogo.model.Device device = createDeviceDirect(product.getId());
        Long contractId = approveDirect(customerEmail, device.getId());

        var pending = paymentService.initiate(customer,
                new PaymentInitiateRequest(contractId, new BigDecimal("500"),
                        PaymentChannel.MPESA, MockProviderScenario.PENDING),
                UUID.randomUUID().toString());
        paymentService.applyProviderResult(pending.transactionReference(),
                com.tracom.lipamdogo.payment.ProviderOutcome.SUCCESS, null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> futures = List.of(pool.submit(() -> {
                ready.countDown();
                go.await();
                return postingService.post(pending.transactionReference()).receiptNumber();
            }), pool.submit(() -> {
                ready.countDown();
                go.await();
                return postingService.post(pending.transactionReference()).receiptNumber();
            }));
            ready.await();
            go.countDown();
            String first = futures.get(0).get();
            String second = futures.get(1).get();
            assertThat(first).isEqualTo(second);
        } finally {
            pool.shutdown();
        }

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountPaid()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(contract.getAmountPaid().add(contract.getAmountRemaining()))
                .isEqualByComparingTo(contract.getTotalPayable());
    }

    @Test
    void concurrentPaymentsOnSameContractSumCorrectly() throws Exception {
        String customerEmail = uniqueEmail("conc-multi");
        registerCustomerDirect(customerEmail);
        UserPrincipal customer = principalFor(customerEmail);

        com.tracom.lipamdogo.model.Product product = productRepository.findAll().get(0);
        com.tracom.lipamdogo.model.Device device = createDeviceDirect(product.getId());
        Long contractId = approveDirect(customerEmail, device.getId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = List.of(pool.submit(() -> {
                ready.countDown();
                go.await();
                paymentService.initiate(customer,
                        new PaymentInitiateRequest(contractId, new BigDecimal("400"),
                                PaymentChannel.MPESA, MockProviderScenario.SUCCESS),
                        UUID.randomUUID().toString());
                return null;
            }), pool.submit(() -> {
                ready.countDown();
                go.await();
                paymentService.initiate(customer,
                        new PaymentInitiateRequest(contractId, new BigDecimal("600"),
                                PaymentChannel.MPESA, MockProviderScenario.SUCCESS),
                        UUID.randomUUID().toString());
                return null;
            }));
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountPaid()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(contract.getAmountPaid().add(contract.getAmountRemaining()))
                .isEqualByComparingTo(contract.getTotalPayable());
    }

    // --- direct (non-MockMvc) helpers for thread-safe tests ---

    @Autowired
    private com.tracom.lipamdogo.service.AuthService authService;

    @Autowired
    private com.tracom.lipamdogo.service.DeviceService deviceService;

    @Autowired
    private com.tracom.lipamdogo.service.FinancingService financingService;

    @Autowired
    private com.tracom.lipamdogo.service.AdminService adminService;

    private void registerCustomerDirect(String email) {
        authService.register(new com.tracom.lipamdogo.dto.UserRegistrationRequest(
                "Concurrent User", email, "+254700000000", "Test1234"));
    }

    private com.tracom.lipamdogo.model.Device createDeviceDirect(Long productId) {
        var response = deviceService.createDevice(new com.tracom.lipamdogo.dto.DeviceRequest(
                productId,
                String.format("351%012d", IMEI_SEQUENCE.incrementAndGet()),
                "PC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()));
        return deviceRepository.findById(response.id()).orElseThrow();
    }

    private Long approveDirect(String customerEmail, Long deviceId) {
        UserPrincipal customer = principalFor(customerEmail);
        String officerEmail = uniqueEmail("conc-officer");
        adminService.createUser(new com.tracom.lipamdogo.dto.AdminUserRequest(
                "Conc Officer", officerEmail, "+254700000000", "Test1234",
                com.tracom.lipamdogo.model.Role.LOAN_OFFICER));
        UserPrincipal officer = principalFor(officerEmail);
        Long planId = paymentPlanRepository.findAll().get(0).getId();
        var application = financingService.submitApplication(customer,
                new com.tracom.lipamdogo.dto.FinancingApplicationRequest(
                        deviceId, new BigDecimal("4000"), planId, null));
        financingService.startReview(officer, application.id());
        return financingService.approveApplication(officer, application.id()).id();
    }
}
