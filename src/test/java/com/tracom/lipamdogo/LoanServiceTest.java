package com.tracom.lipamdogo;

import com.tracom.lipamdogo.dto.AdminUserRequest;
import com.tracom.lipamdogo.dto.DeviceRequest;
import com.tracom.lipamdogo.dto.FinancingApplicationRequest;
import com.tracom.lipamdogo.dto.FinancingApplicationResponse;
import com.tracom.lipamdogo.dto.LoanContractResponse;
import com.tracom.lipamdogo.dto.PaymentInitiateRequest;
import com.tracom.lipamdogo.model.Device;
import com.tracom.lipamdogo.model.DeviceStatus;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.Product;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.payment.MockProviderScenario;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.repository.ProductRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.AdminService;
import com.tracom.lipamdogo.service.AuthService;
import com.tracom.lipamdogo.service.DeviceService;
import com.tracom.lipamdogo.service.FinancingService;
import com.tracom.lipamdogo.service.LoanService;
import com.tracom.lipamdogo.service.PaymentService;
import com.tracom.lipamdogo.dto.UserRegistrationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LoanServiceTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(700000);

    @Autowired
    private LoanService loanService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AuthService authService;

    @Autowired
    private AdminService adminService;

    @Autowired
    private FinancingService financingService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private com.tracom.lipamdogo.repository.PaymentPlanRepository paymentPlanRepository;

    private UserPrincipal registerCustomer(String name) {
        String email = "loan-" + UUID.randomUUID() + "@example.com";
        authService.register(new UserRegistrationRequest(name, email, "+254700000000", "Test1234"));
        UserAccount account = userAccountRepository.findByEmail(email).orElseThrow();
        return new UserPrincipal(account);
    }

    private UserPrincipal createOfficer() {
        String email = "officer-" + UUID.randomUUID() + "@example.com";
        var profile = adminService.createUser(new AdminUserRequest(
                "Loan Officer", email, "+254700000000", "Test1234", Role.LOAN_OFFICER));
        return new UserPrincipal(userAccountRepository.findById(profile.id()).orElseThrow());
    }

    private Device createAvailableDevice() {
        Product product = productRepository.findAll().get(0);
        var response = deviceService.createDevice(new DeviceRequest(
                product.getId(),
                String.format("355%012d", IMEI_SEQUENCE.incrementAndGet()),
                "LN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()));
        return deviceRepository.findById(response.id()).orElseThrow();
    }

    private Long firstPlanId() {
        return paymentPlanRepository.findAll().get(0).getId();
    }

    private LoanContractResponse approveContractFor(UserPrincipal customer, UserPrincipal officer, Device device) {
        FinancingApplicationResponse application = financingService.submitApplication(customer,
                new FinancingApplicationRequest(device.getId(), new BigDecimal("4000"), firstPlanId(), null));
        financingService.startReview(officer, application.id());
        return financingService.approveApplication(officer, application.id());
    }

    private com.tracom.lipamdogo.dto.PaymentTransactionResponse pay(UserPrincipal customer,
                                                                    Long contractId,
                                                                    BigDecimal amount) {
        return paymentService.initiate(customer,
                new PaymentInitiateRequest(contractId, amount, PaymentChannel.MPESA, MockProviderScenario.SUCCESS),
                UUID.randomUUID().toString());
    }

    @Test
    void shouldApplyPostedPaymentAndReduceBalance() {
        UserPrincipal customer = registerCustomer("Jane Doe");
        UserPrincipal officer = createOfficer();
        LoanContractResponse contract = approveContractFor(customer, officer, createAvailableDevice());

        var posted = pay(customer, contract.id(), BigDecimal.valueOf(500));

        assertThat(posted.status().name()).isEqualTo("POSTED");
        assertThat(posted.receiptNumber()).startsWith("RCPT-");

        LoanContractResponse updated = loanService.getContract(customer, contract.id());
        assertThat(updated.amountRemaining()).isEqualByComparingTo(contract.amountRemaining().subtract(BigDecimal.valueOf(500)));
        assertThat(updated.loanStatus()).isEqualTo(LoanStatus.ACTIVE.name());
    }

    @Test
    void shouldCountPartialPaymentTowardObligation() {
        UserPrincipal customer = registerCustomer("Aisha");
        UserPrincipal officer = createOfficer();
        LoanContractResponse contract = approveContractFor(customer, officer, createAvailableDevice());

        // 50 < 70 daily installment: the old engine discarded this and went
        // OVERDUE. The new engine credits every posted shilling.
        pay(customer, contract.id(), BigDecimal.valueOf(50));

        LoanContractResponse updated = loanService.getContract(customer, contract.id());
        assertThat(updated.amountRemaining()).isEqualByComparingTo(contract.amountRemaining().subtract(BigDecimal.valueOf(50)));
        assertThat(updated.loanStatus()).isEqualTo(LoanStatus.ACTIVE.name());
    }

    @Test
    void shouldMarkLoanPaidOffWhenFullBalanceIsCleared() {
        UserPrincipal customer = registerCustomer("Alex");
        UserPrincipal officer = createOfficer();
        LoanContractResponse contract = approveContractFor(customer, officer, createAvailableDevice());

        pay(customer, contract.id(), contract.amountRemaining());

        LoanContractResponse updated = loanService.getContract(customer, contract.id());
        assertThat(updated.amountRemaining()).isZero();
        assertThat(updated.loanStatus()).isEqualTo(LoanStatus.PAID_OFF.name());

        Device device = deviceRepository.findById(contract.deviceId()).orElseThrow();
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.PAID_OFF);
    }

    @Test
    void shouldKeepApprovedContractsIndependent() {
        UserPrincipal customer = registerCustomer("Multi Contract");
        UserPrincipal officer = createOfficer();

        LoanContractResponse first = approveContractFor(customer, officer, createAvailableDevice());
        LoanContractResponse second = approveContractFor(customer, officer, createAvailableDevice());

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.deviceId()).isNotEqualTo(second.deviceId());

        List<LoanContractResponse> mine = loanService.getMyContracts(customer);
        assertThat(mine.stream().map(LoanContractResponse::id)).contains(first.id(), second.id());

        pay(customer, first.id(), first.amountRemaining());

        LoanContractResponse paid = loanService.getContract(customer, first.id());
        assertThat(paid.loanStatus()).isEqualTo(LoanStatus.PAID_OFF.name());

        LoanContractResponse untouched = loanService.getContract(customer, second.id());
        assertThat(untouched.loanStatus()).isEqualTo(LoanStatus.ACTIVE.name());
    }
}
