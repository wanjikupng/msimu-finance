package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import com.tracom.lipamdogo.dto.PaymentInitiateRequest;
import com.tracom.lipamdogo.model.AllocationStatus;
import com.tracom.lipamdogo.model.DeviceStatus;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.model.PaymentAllocation;
import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.PaymentTransactionStatus;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4.1B: explicit per-day payment allocations and allocation-based
 * reversal. Fixture arithmetic uses daily=70 (Classic plan) and 14000
 * remaining unless a test states otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentAllocationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(920000);

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
    private com.tracom.lipamdogo.repository.PaymentAllocationRepository allocationRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.DeviceRepository deviceRepository;

    @Autowired
    private com.tracom.lipamdogo.service.PaymentService paymentService;

    @Autowired
    private com.tracom.lipamdogo.service.PaymentPostingService postingService;

    @Autowired
    private com.tracom.lipamdogo.service.AuthService authService;

    @Autowired
    private com.tracom.lipamdogo.service.DeviceService deviceService;

    @Autowired
    private com.tracom.lipamdogo.service.FinancingService financingService;

    @Autowired
    private com.tracom.lipamdogo.service.AdminService adminService;

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
                                        String.format("353%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "PA-" + UUID.randomUUID().toString().replace("-", "")
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

    /** Initiate with immediate SUCCESS+auto-post; returns the reference. */
    private String payPosted(String token, Long contractId, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": %s, \"channel\": \"MPESA\"}".formatted(contractId, amount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.transactionReference");
    }

    private List<PaymentAllocation> activeRows(Long contractId) {
        return allocationRepository.findAllByContractIdAndStatusOrderByIdAsc(contractId, AllocationStatus.ACTIVE);
    }

    private UserPrincipal principalFor(String email) {
        return new UserPrincipal(userAccountRepository.findByEmail(email).orElseThrow());
    }

    @Test
    void singleInstallmentCreatesOneAllocation() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Alloc Simple", uniqueEmail("alloc-simple"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        LoanContract before = loanContractRepository.findById(contractId).orElseThrow();

        String reference = payPosted(customerToken, contractId, "70");

        List<PaymentAllocation> rows = activeRows(contractId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(rows.get(0).getAllocationDate()).isEqualTo(before.getNextPaymentDueDate());
        assertThat(rows.get(0).getSequence()).isEqualTo(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(AllocationStatus.ACTIVE);
        // Invariant 1: active allocations sum to the transaction amount.
        assertThat(rows.stream().map(PaymentAllocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("70"));

        var tx = transactionRepository.findByTransactionReference(reference).orElseThrow();
        assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("70"));
    }

    @Test
    void partialPaymentsAllocateAndCompleteADay() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Alloc Partial", uniqueEmail("alloc-partial"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        LocalDate dayZero = loanContractRepository.findById(contractId).orElseThrow().getNextPaymentDueDate();

        payPosted(customerToken, contractId, "60");
        List<PaymentAllocation> afterFirst = activeRows(contractId);
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(afterFirst.get(0).getAllocationDate()).isEqualTo(dayZero);
        assertThat(loanContractRepository.findById(contractId).orElseThrow().getNextPaymentDueDate())
                .isEqualTo(dayZero);

        payPosted(customerToken, contractId, "40");
        List<PaymentAllocation> afterSecond = activeRows(contractId);
        // Second payment completes dayZero (+10) and starts the next day (+30).
        assertThat(afterSecond).hasSize(3);
        assertThat(afterSecond.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(afterSecond.get(1).getAllocationDate()).isEqualTo(dayZero);
        assertThat(afterSecond.get(2).getAmount()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(afterSecond.get(2).getAllocationDate()).isEqualTo(dayZero.plusDays(1));
        // 60 + 40 covers exactly one 70 installment: due advances one day,
        // 30 carries forward.
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getNextPaymentDueDate()).isEqualTo(dayZero.plusDays(1));
        assertThat(contract.getInstallmentPaid()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("13900"));
    }

    @Test
    void multiInstallmentPaymentSpansDays() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Alloc Multi", uniqueEmail("alloc-multi"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        LocalDate dayZero = loanContractRepository.findById(contractId).orElseThrow().getNextPaymentDueDate();

        payPosted(customerToken, contractId, "250");

        // 250 at daily 70 = three full days plus a 40 remainder.
        List<PaymentAllocation> rows = activeRows(contractId);
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(rows.get(0).getAllocationDate()).isEqualTo(dayZero);
        assertThat(rows.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(rows.get(1).getAllocationDate()).isEqualTo(dayZero.plusDays(1));
        assertThat(rows.get(2).getAmount()).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(rows.get(2).getAllocationDate()).isEqualTo(dayZero.plusDays(2));
        assertThat(rows.get(3).getAmount()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(rows.get(3).getAllocationDate()).isEqualTo(dayZero.plusDays(3));
        // Invariant 4: rows sum exactly to the payment.
        assertThat(rows.stream().map(PaymentAllocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("250"));

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getNextPaymentDueDate()).isEqualTo(dayZero.plusDays(3));
        assertThat(contract.getInstallmentPaid()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("13750"));
    }

    @Test
    void multiplePaymentsAllocateDeterministically() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Alloc Deterministic", uniqueEmail("alloc-determ"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        LocalDate dayZero = loanContractRepository.findById(contractId).orElseThrow().getNextPaymentDueDate();

        String refA = payPosted(customerToken, contractId, "60");
        String refB = payPosted(customerToken, contractId, "40");
        String refC = payPosted(customerToken, contractId, "200");

        var rowsA = allocationRepository.findByPaymentTransactionId(
                transactionRepository.findByTransactionReference(refA).orElseThrow().getId());
        var rowsB = allocationRepository.findByPaymentTransactionId(
                transactionRepository.findByTransactionReference(refB).orElseThrow().getId());
        var rowsC = allocationRepository.findByPaymentTransactionId(
                transactionRepository.findByTransactionReference(refC).orElseThrow().getId());

        assertThat(rowsA).hasSize(1);
        assertThat(rowsA.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(rowsA.get(0).getAllocationDate()).isEqualTo(dayZero);

        // B completes dayZero (+10) and starts the next day (+30).
        assertThat(rowsB).hasSize(2);
        assertThat(rowsB.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(rowsB.get(0).getAllocationDate()).isEqualTo(dayZero);
        assertThat(rowsB.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(rowsB.get(1).getAllocationDate()).isEqualTo(dayZero.plusDays(1));

        // C carries 30 in, so it fills day+1 (+40), covers two full days,
        // and carries 20: 40 + 70 + 70 + 20.
        assertThat(rowsC).hasSize(4);
        assertThat(rowsC.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(rowsC.get(0).getAllocationDate()).isEqualTo(dayZero.plusDays(1));
        assertThat(rowsC.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(rowsC.get(1).getAllocationDate()).isEqualTo(dayZero.plusDays(2));
        assertThat(rowsC.get(2).getAmount()).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(rowsC.get(2).getAllocationDate()).isEqualTo(dayZero.plusDays(3));
        assertThat(rowsC.get(3).getAmount()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(rowsC.get(3).getAllocationDate()).isEqualTo(dayZero.plusDays(4));

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getNextPaymentDueDate()).isEqualTo(dayZero.plusDays(4));
        assertThat(contract.getInstallmentPaid()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("13700"));
    }

    @Test
    void reversalRestoresExactPriorSchedule() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Alloc Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Alloc Reverse", uniqueEmail("alloc-reverse"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        LoanContract before = loanContractRepository.findById(contractId).orElseThrow();
        LocalDate dayZero = before.getNextPaymentDueDate();

        String reference = payPosted(customerToken, contractId, "250");
        assertThat(activeRows(contractId)).hasSize(4);

        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Provider reversal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));

        // Rows preserved as history, marked reversed — Invariant 2 (effective 0).
        var tx = transactionRepository.findByTransactionReference(reference).orElseThrow();
        assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("250"));
        var allRows = allocationRepository.findByPaymentTransactionId(tx.getId());
        assertThat(allRows).hasSize(4);
        assertThat(allRows).allMatch(r -> r.getStatus() == AllocationStatus.REVERSED);
        assertThat(allRows).allMatch(r -> r.getReversedAt() != null);
        assertThat(allocationRepository.findByPaymentTransactionIdAndStatus(tx.getId(), AllocationStatus.ACTIVE))
                .isEmpty();

        // Invariant 5: balance and schedule exactly as if never posted.
        LoanContract after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(before.getAmountRemaining());
        assertThat(after.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(after.getNextPaymentDueDate()).isEqualTo(dayZero);
        assertThat(after.getInstallmentPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
    }

    @Test
    void partialReversalRestoresOriginalObligation() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Alloc Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Alloc Partial Rev", uniqueEmail("alloc-partrev"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        LocalDate dayZero = loanContractRepository.findById(contractId).orElseThrow().getNextPaymentDueDate();

        String reference = payPosted(customerToken, contractId, "60");

        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Duplicate\"}"))
                .andExpect(status().isOk());

        LoanContract after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(after.getNextPaymentDueDate()).isEqualTo(dayZero);
        assertThat(after.getInstallmentPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(activeRows(contractId)).isEmpty();
    }

    @Test
    void reversingOnePaymentLeavesOthersIntact() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Alloc Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Alloc Independent", uniqueEmail("alloc-indep"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        LocalDate dayZero = loanContractRepository.findById(contractId).orElseThrow().getNextPaymentDueDate();

        String refA = payPosted(customerToken, contractId, "200");
        String refB = payPosted(customerToken, contractId, "100");
        // A: D0=70,D1=70,D2=60 → due D0+2, credit 60. B fills D2 (+10),
        // covers D3, carries 20 → due D0+4, credit 20. Six rows total.
        assertThat(activeRows(contractId)).hasSize(3 + 3);

        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", refA)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Provider reversal\"}"))
                .andExpect(status().isOk());

        // Invariant 3: B's rows untouched and still ACTIVE.
        var txB = transactionRepository.findByTransactionReference(refB).orElseThrow();
        var rowsB = allocationRepository.findByPaymentTransactionId(txB.getId());
        assertThat(rowsB).allMatch(r -> r.getStatus() == AllocationStatus.ACTIVE);

        // As if only B(100) had ever posted from (dayZero, 0):
        // D0=70, D1=30 → due dayZero+1, credit 30, remaining 13900.
        LoanContract after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("13900"));
        assertThat(after.getAmountPaid()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(after.getNextPaymentDueDate()).isEqualTo(dayZero.plusDays(1));
        assertThat(after.getInstallmentPaid()).isEqualByComparingTo(new BigDecimal("30"));
    }

    @Test
    void overdueReversalRestoresOverdueState() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Alloc Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Alloc Overdue", uniqueEmail("alloc-overdue"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        // Simulate an older contract: anchor and due date in the past.
        LoanContract aged = loanContractRepository.findById(contractId).orElseThrow();
        LocalDate pastDue = LocalDate.now().minusDays(3);
        aged.setScheduleAnchorDate(pastDue);
        aged.setNextPaymentDueDate(pastDue);
        loanContractRepository.save(aged);

        String reference = payPosted(customerToken, contractId, "60");
        LoanContract overdue = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(overdue.getStatus()).isEqualTo(LoanStatus.OVERDUE);

        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Provider reversal\"}"))
                .andExpect(status().isOk());

        LoanContract after = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(after.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(after.getNextPaymentDueDate()).isEqualTo(pastDue);
        assertThat(after.getInstallmentPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(after.getStatus()).isEqualTo(LoanStatus.OVERDUE);
    }

    @Test
    void payoffReversalIsRefusedWithoutStateChange() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Alloc Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Alloc Payoff", uniqueEmail("alloc-payoff"));
        Long deviceId = createDevice(adminToken);
        Long contractId = approveContractFor(customerToken, officerToken, deviceId);

        String reference = payPosted(customerToken, contractId, "14000");
        LoanContract paid = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(deviceRepository.findById(deviceId).orElseThrow().getStatus())
                .isEqualTo(DeviceStatus.PAID_OFF);
        int rowsBefore = allocationRepository.findByContractId(contractId).size();

        // Invariant 6: refusal, no partial state change.
        MvcResult refused = mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Customer claims duplicate\"}"))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("completed the financing contract");

        var tx = transactionRepository.findByTransactionReference(reference).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(PaymentTransactionStatus.POSTED);
        LoanContract unchanged = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(unchanged.getAmountRemaining()).isZero();
        assertThat(deviceRepository.findById(deviceId).orElseThrow().getStatus())
                .isEqualTo(DeviceStatus.PAID_OFF);
        assertThat(allocationRepository.findByContractId(contractId)).hasSize(rowsBefore);
        assertThat(activeRows(contractId).stream().map(PaymentAllocation::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("14000"));
    }

    @Test
    void duplicateReversalIsRejected() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Alloc Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Alloc Dup Rev", uniqueEmail("alloc-duprev"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String reference = payPosted(customerToken, contractId, "500");
        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"First reversal\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Second reversal\"}"))
                .andExpect(status().isBadRequest());

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
    }

    @Test
    void receiptSurvivesReversal() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Alloc Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Alloc Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Alloc Receipt", uniqueEmail("alloc-receipt"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        String reference = payPosted(customerToken, contractId, "500");
        String receiptNumber = JsonPath.read(
                mockMvc.perform(get("/api/v1/payments/{reference}/receipt", reference)
                                .header("Authorization", "Bearer " + customerToken))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                "$.receiptNumber");

        mockMvc.perform(post("/api/v1/payments/{reference}/reverse", reference)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Provider reversal\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/payments/{reference}/receipt", reference)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value(receiptNumber))
                .andExpect(jsonPath("$.amount").value(500.0));
    }

    @Test
    void concurrentReversalsLeaveOneCoherentState() throws Exception {
        String customerEmail = uniqueEmail("alloc-conc-rev");
        registerCustomerDirect(customerEmail);
        UserPrincipal customer = principalFor(customerEmail);

        com.tracom.lipamdogo.model.Product product = productRepository.findAll().get(0);
        com.tracom.lipamdogo.model.Device device = createDeviceDirect(product.getId());
        Long contractId = approveDirect(customerEmail, device.getId());

        var posted = paymentService.initiate(customer,
                new PaymentInitiateRequest(contractId, new BigDecimal("500"),
                        PaymentChannel.MPESA, MockProviderScenario.SUCCESS),
                UUID.randomUUID().toString());
        assertThat(posted.status().name()).isEqualTo("POSTED");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<String> firstOutcome = new AtomicReference<>();
        AtomicReference<String> secondOutcome = new AtomicReference<>();
        try {
            List<Future<?>> futures = List.of(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    postingService.reverse(posted.transactionReference(), "Race reversal A");
                    firstOutcome.set("reversed");
                } catch (Exception e) {
                    firstOutcome.set("rejected:" + e.getClass().getSimpleName());
                }
                return null;
            }), pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    postingService.reverse(posted.transactionReference(), "Race reversal B");
                    secondOutcome.set("reversed");
                } catch (Exception e) {
                    secondOutcome.set("rejected:" + e.getClass().getSimpleName());
                }
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

        long reversed = List.of(firstOutcome.get(), secondOutcome.get()).stream()
                .filter("reversed"::equals).count();
        assertThat(reversed).isEqualTo(1);

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(contract.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(activeRows(contractId)).isEmpty();
        var tx = transactionRepository.findByTransactionReference(posted.transactionReference()).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(PaymentTransactionStatus.REVERSED);
    }

    @Test
    void concurrentPostAndReverseStayCoherent() throws Exception {
        String customerEmail = uniqueEmail("alloc-conc-postrev");
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
        AtomicReference<String> postOutcome = new AtomicReference<>();
        AtomicReference<String> reverseOutcome = new AtomicReference<>();
        try {
            List<Future<?>> futures = List.of(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    postingService.post(pending.transactionReference());
                    postOutcome.set("posted");
                } catch (Exception e) {
                    postOutcome.set("rejected:" + e.getClass().getSimpleName());
                }
                return null;
            }), pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    postingService.reverse(pending.transactionReference(), "Race reversal");
                    reverseOutcome.set("reversed");
                } catch (Exception e) {
                    reverseOutcome.set("rejected:" + e.getClass().getSimpleName());
                }
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

        // Reverse requires POSTED, so it always loses a clean race — but if
        // post wins first, reverse may then legally succeed. All three final
        // states are coherent; assert the invariant in each.
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        var tx = transactionRepository.findByTransactionReference(pending.transactionReference()).orElseThrow();
        assertThat(contract.getAmountPaid().add(contract.getAmountRemaining()))
                .isEqualByComparingTo(contract.getTotalPayable());
        if (tx.getStatus() == PaymentTransactionStatus.POSTED) {
            assertThat(contract.getAmountPaid()).isEqualByComparingTo(new BigDecimal("500"));
            // 500 at daily 70 spans eight day slices (7 x 70 + 10).
            assertThat(activeRows(contractId)).hasSize(8);
        } else {
            // SUCCESS (reverse lost immediately) or REVERSED (reverse won
            // after posting): no current financial effect either way.
            assertThat(contract.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(activeRows(contractId)).isEmpty();
        }
    }

    // --- direct (non-MockMvc) helpers for thread-safe tests ---

    private void registerCustomerDirect(String email) {
        authService.register(new com.tracom.lipamdogo.dto.UserRegistrationRequest(
                "Allocation User", email, "+254700000000", "Test1234"));
    }

    private com.tracom.lipamdogo.model.Device createDeviceDirect(Long productId) {
        var response = deviceService.createDevice(new com.tracom.lipamdogo.dto.DeviceRequest(
                productId,
                String.format("354%012d", IMEI_SEQUENCE.incrementAndGet()),
                "AL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()));
        return deviceRepository.findById(response.id()).orElseThrow();
    }

    private Long approveDirect(String customerEmail, Long deviceId) {
        UserPrincipal customer = principalFor(customerEmail);
        String officerEmail = uniqueEmail("alloc-officer");
        adminService.createUser(new com.tracom.lipamdogo.dto.AdminUserRequest(
                "Alloc Officer", officerEmail, "+254700000000", "Test1234",
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
