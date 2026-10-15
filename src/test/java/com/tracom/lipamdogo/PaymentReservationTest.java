package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import com.tracom.lipamdogo.dto.PaymentInitiateRequest;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.PaymentTransaction;
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
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4.1A: pending/unposted payments reserve contract capacity so later
 * payments can never consume the same balance twice.
 *
 * <p>Invariant under test: posted + reserved(unposted) + available = total,
 * i.e. posted + reserved <= original obligation at all times.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentReservationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(900000);

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

    @Autowired
    private com.tracom.lipamdogo.service.AuthService authService;

    @Autowired
    private com.tracom.lipamdogo.service.DeviceService deviceService;

    @Autowired
    private com.tracom.lipamdogo.service.FinancingService financingService;

    @Autowired
    private com.tracom.lipamdogo.service.AdminService adminService;

    @Autowired
    private com.tracom.lipamdogo.repository.DeviceRepository deviceRepository;

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
                                        String.format("359%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "PR-" + UUID.randomUUID().toString().replace("-", "")
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

    private MvcResult initiate(String token, String body, String idempotencyKey) throws Exception {
        var req = post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            req = req.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(req).andReturn();
    }

    private String payBody(Long contractId, String amount, String scenario) {
        return "{\"contractId\": %d, \"amount\": %s, \"channel\": \"MPESA\", \"mockScenario\": \"%s\"}"
                .formatted(contractId, amount, scenario);
    }

    private BigDecimal reservedOf(Long contractId) {
        return transactionRepository.sumReservedByContractId(contractId, EnumSet.of(
                PaymentTransactionStatus.INITIATED,
                PaymentTransactionStatus.PENDING,
                PaymentTransactionStatus.SUCCESS));
    }

    private UserPrincipal principalFor(String email) {
        return new UserPrincipal(userAccountRepository.findByEmail(email).orElseThrow());
    }

    @Test
    void fullReservationBlocksSecondPayment() throws Exception {
        // Fixture contracts carry 14000 remaining, so full reservation = 14000.
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Res Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Res Full", uniqueEmail("res-full"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        MvcResult first = initiate(customerToken, payBody(contractId, "14000", "PENDING"), UUID.randomUUID().toString());
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(first.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("PENDING");

        MvcResult second = initiate(customerToken, payBody(contractId, "1000", "PENDING"), UUID.randomUUID().toString());
        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(second.getResponse().getContentAsString()).contains("still being processed");

        // Balance untouched, reservation recorded.
        assertThat(reservedOf(contractId)).isEqualByComparingTo(new BigDecimal("14000"));
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(transactionRepository.findAllByContractId(contractId)).hasSize(1);
    }

    @Test
    void partialReservationAllowsOnlyFittingPayments() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Res Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Res Partial", uniqueEmail("res-partial"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        MvcResult first = initiate(customerToken, payBody(contractId, "2500", "PENDING"), UUID.randomUUID().toString());
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // 11500 available: 1000 fits (FAILED scenario releases immediately).
        MvcResult fitting = initiate(customerToken, payBody(contractId, "1000", "FAILED"), UUID.randomUUID().toString());
        assertThat(fitting.getResponse().getStatus()).isEqualTo(201);

        // 12000 does not fit into the 11500 available.
        MvcResult tooBig = initiate(customerToken, payBody(contractId, "12000", "PENDING"), UUID.randomUUID().toString());
        assertThat(tooBig.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooBig.getResponse().getContentAsString()).contains("Only KES 11500");

        assertThat(reservedOf(contractId)).isEqualByComparingTo(new BigDecimal("2500"));
    }

    @Test
    void failedPaymentReleasesReservation() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Res Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Res Failed", uniqueEmail("res-failed"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        MvcResult first = initiate(customerToken, payBody(contractId, "14000", "PENDING"), UUID.randomUUID().toString());
        String reference = JsonPath.read(first.getResponse().getContentAsString(), "$.transactionReference");

        // While reserved, even 1000 is blocked.
        MvcResult blocked = initiate(customerToken, payBody(contractId, "1000", "PENDING"), UUID.randomUUID().toString());
        assertThat(blocked.getResponse().getStatus()).isEqualTo(400);

        mockMvc.perform(post("/api/v1/payments/{reference}/provider-result", reference)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"FAILED\"}"))
                .andExpect(status().isOk());

        assertThat(reservedOf(contractId)).isEqualByComparingTo(BigDecimal.ZERO);

        // Released: the full balance is payable again.
        MvcResult retry = initiate(customerToken, payBody(contractId, "14000", "PENDING"), UUID.randomUUID().toString());
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);

        // Failure moved no money.
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(contract.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void postedPaymentConsumesReservation() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Res Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Res Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Res Posted", uniqueEmail("res-posted"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        MvcResult first = initiate(customerToken, payBody(contractId, "4000", "PENDING"), UUID.randomUUID().toString());
        String reference = JsonPath.read(first.getResponse().getContentAsString(), "$.transactionReference");

        mockMvc.perform(post("/api/v1/payments/{reference}/provider-result", reference)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/payments/{reference}/post", reference)
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk());

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(contract.getAmountPaid()).isEqualByComparingTo(new BigDecimal("4000"));
        assertThat(reservedOf(contractId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void successUnpostedKeepsReserving() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Res Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Res Success", uniqueEmail("res-success"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        MvcResult first = initiate(customerToken, payBody(contractId, "14000", "PENDING"), UUID.randomUUID().toString());
        String reference = JsonPath.read(first.getResponse().getContentAsString(), "$.transactionReference");

        mockMvc.perform(post("/api/v1/payments/{reference}/provider-result", reference)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(status().isOk());

        // SUCCESS but not POSTED: still fully reserved, balance untouched.
        MvcResult second = initiate(customerToken, payBody(contractId, "1", "PENDING"), UUID.randomUUID().toString());
        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(reservedOf(contractId)).isEqualByComparingTo(new BigDecimal("14000"));
    }

    @Test
    void idempotencyRetryWhilePendingCreatesNoSecondReservation() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Res Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Res Idem", uniqueEmail("res-idem"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));
        String key = "idem-" + UUID.randomUUID();

        MvcResult first = initiate(customerToken, payBody(contractId, "4000", "PENDING"), key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstRef = JsonPath.read(first.getResponse().getContentAsString(), "$.transactionReference");

        MvcResult retry = initiate(customerToken, payBody(contractId, "4000", "PENDING"), key);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        String retryRef = JsonPath.read(retry.getResponse().getContentAsString(), "$.transactionReference");
        assertThat(retryRef).isEqualTo(firstRef);

        assertThat(transactionRepository.findAllByContractId(contractId)).hasSize(1);
        assertThat(reservedOf(contractId)).isEqualByComparingTo(new BigDecimal("4000"));
    }

    @Test
    void duplicateProviderCallbackPostsOnce() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Res Officer", "LOAN_OFFICER");
        String operatorToken = createStaff(adminToken, "Res Operator", "PAYMENTS_OPERATOR");
        String customerToken = registerAndLogin("Res Callback", uniqueEmail("res-callback"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        MvcResult first = initiate(customerToken, payBody(contractId, "4000", "PENDING"), UUID.randomUUID().toString());
        String reference = JsonPath.read(first.getResponse().getContentAsString(), "$.transactionReference");

        mockMvc.perform(post("/api/v1/payments/{reference}/provider-result", reference)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/payments/{reference}/provider-result", reference)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"SUCCESS\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/payments/{reference}/post", reference)
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk());

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountPaid()).isEqualByComparingTo(new BigDecimal("4000"));
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(reservedOf(contractId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void concurrentInitiationsCannotOverReserve() throws Exception {
        // Two simultaneous 8000 requests against 14000: only one may win
        // (8000 + 8000 would overcommit the balance).
        String customerEmail = uniqueEmail("res-conc");
        registerCustomerDirect(customerEmail);
        UserPrincipal customer = principalFor(customerEmail);

        com.tracom.lipamdogo.model.Product product = productRepository.findAll().get(0);
        com.tracom.lipamdogo.model.Device device = createDeviceDirect(product.getId());
        Long contractId = approveDirect(customerEmail, device.getId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> futures = List.of(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    var response = paymentService.initiate(customer,
                            new PaymentInitiateRequest(contractId, new BigDecimal("8000"),
                                    PaymentChannel.MPESA, MockProviderScenario.PENDING),
                            UUID.randomUUID().toString());
                    return "ok:" + response.transactionReference();
                } catch (Exception e) {
                    return "rejected:" + e.getMessage();
                }
            }), pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    var response = paymentService.initiate(customer,
                            new PaymentInitiateRequest(contractId, new BigDecimal("8000"),
                                    PaymentChannel.MPESA, MockProviderScenario.PENDING),
                            UUID.randomUUID().toString());
                    return "ok:" + response.transactionReference();
                } catch (Exception e) {
                    return "rejected:" + e.getMessage();
                }
            }));
            ready.await();
            go.countDown();
            String first = futures.get(0).get();
            String second = futures.get(1).get();
            long accepted = List.of(first, second).stream().filter(r -> r.startsWith("ok:")).count();
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdown();
        }

        // Never overcommitted: a single 8000 reservation against 14000.
        assertThat(transactionRepository.findAllByContractId(contractId)).hasSize(1);
        assertThat(reservedOf(contractId)).isEqualByComparingTo(new BigDecimal("8000"));
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
    }

    @Test
    void invariantHoldsAcrossPostAndReserve() throws Exception {
        String adminToken = loginAsAdmin();
        String officerToken = createStaff(adminToken, "Res Officer", "LOAN_OFFICER");
        String customerToken = registerAndLogin("Res Invariant", uniqueEmail("res-invariant"));
        Long contractId = approveContractFor(customerToken, officerToken, createDevice(adminToken));

        // 2500 reserved (pending) + 1000 posted.
        initiate(customerToken, payBody(contractId, "2500", "PENDING"), UUID.randomUUID().toString());
        initiate(customerToken, "{\"contractId\": %d, \"amount\": 1000, \"channel\": \"MPESA\"}".formatted(contractId),
                UUID.randomUUID().toString());

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        BigDecimal posted = contract.getAmountPaid();
        BigDecimal reserved = reservedOf(contractId);
        BigDecimal remaining = contract.getAmountRemaining();
        BigDecimal available = remaining.subtract(reserved);

        assertThat(posted).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(reserved).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(posted.add(reserved).add(available)).isEqualByComparingTo(contract.getTotalPayable());
        assertThat(posted.add(reserved)).isLessThanOrEqualTo(contract.getTotalPayable());
    }

    @Test
    void postingOversizedTransactionFailsLoudlyWithoutApplying() {
        String customerEmail = uniqueEmail("res-oversize");
        registerCustomerDirect(customerEmail);
        UserPrincipal customer = principalFor(customerEmail);

        com.tracom.lipamdogo.model.Product product = productRepository.findAll().get(0);
        com.tracom.lipamdogo.model.Device device = createDeviceDirect(product.getId());
        Long contractId = approveDirect(customerEmail, device.getId());
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();

        // Craft an inconsistent SUCCESS row directly: 20000 against 14000.
        // Bypasses initiation (which would now reject it) to prove posting
        // refuses partial application instead of silently trimming via min().
        PaymentTransaction rogue = new PaymentTransaction();
        rogue.setContract(contract);
        rogue.setCustomer(contract.getCustomer());
        rogue.setAmount(new BigDecimal("20000"));
        rogue.setChannel(PaymentChannel.MPESA);
        rogue.setStatus(PaymentTransactionStatus.SUCCESS);
        rogue.setTransactionReference("PAY-ROGUE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        rogue.setProviderReference("MPESA-MOCK-ROGUE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        rogue.setIdempotencyKey(UUID.randomUUID().toString());
        PaymentTransaction saved = transactionRepository.saveAndFlush(rogue);

        assertThatThrownBy(() -> postingService.post(saved.getTransactionReference()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires investigation");

        LoanContract unchanged = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(unchanged.getAmountRemaining()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(unchanged.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(transactionRepository.findByTransactionReference(saved.getTransactionReference())
                .orElseThrow().getStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS);
    }

    // --- direct (non-MockMvc) helpers for thread-safe tests ---

    private void registerCustomerDirect(String email) {
        authService.register(new com.tracom.lipamdogo.dto.UserRegistrationRequest(
                "Reservation User", email, "+254700000000", "Test1234"));
    }

    private com.tracom.lipamdogo.model.Device createDeviceDirect(Long productId) {
        var response = deviceService.createDevice(new com.tracom.lipamdogo.dto.DeviceRequest(
                productId,
                String.format("350%012d", IMEI_SEQUENCE.incrementAndGet()),
                "PV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()));
        return deviceRepository.findById(response.id()).orElseThrow();
    }

    private Long approveDirect(String customerEmail, Long deviceId) {
        UserPrincipal customer = principalFor(customerEmail);
        String officerEmail = uniqueEmail("res-officer");
        adminService.createUser(new com.tracom.lipamdogo.dto.AdminUserRequest(
                "Res Officer", officerEmail, "+254700000000", "Test1234",
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
