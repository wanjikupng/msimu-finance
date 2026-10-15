package com.tracom.lipamdogo.config;

import com.tracom.lipamdogo.model.CashLoan;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.CashLoanRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Development/demo identity bootstrap.
 *
 * <p>Creates an ADMIN account and a demo CUSTOMER on startup when they do not
 * already exist. Credentials come from configuration (environment variables in
 * production-like setups); the defaults in {@code application.properties} are
 * for the academy/demo environment only. Passwords are BCrypt-hashed and are
 * never logged.</p>
 */
@Component
@Order(1)
public class IdentityBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IdentityBootstrap.class);

    private final UserAccountRepository userAccountRepository;
    private final CashLoanRepository cashLoanRepository;
    private final PasswordEncoder passwordEncoder;

    private final boolean adminEnabled;
    private final String adminEmail;
    private final String adminPassword;
    private final boolean demoEnabled;
    private final String demoEmail;
    private final String demoPassword;

    public IdentityBootstrap(UserAccountRepository userAccountRepository,
                             CashLoanRepository cashLoanRepository,
                             PasswordEncoder passwordEncoder,
                             @Value("${app.bootstrap.admin.enabled:true}") boolean adminEnabled,
                             @Value("${app.bootstrap.admin.email:admin@tracom.local}") String adminEmail,
                             @Value("${app.bootstrap.admin.password:Admin@1234}") String adminPassword,
                             @Value("${app.bootstrap.demo-customer.enabled:true}") boolean demoEnabled,
                             @Value("${app.bootstrap.demo-customer.email:demo@lipamdogo.com}") String demoEmail,
                             @Value("${app.bootstrap.demo-customer.password:Demo@1234}") String demoPassword) {
        this.userAccountRepository = userAccountRepository;
        this.cashLoanRepository = cashLoanRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEnabled = adminEnabled;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.demoEnabled = demoEnabled;
        this.demoEmail = demoEmail;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminEnabled) {
            ensureAccount(adminEmail, adminPassword, "Platform Administrator", "+254700000001",
                    "C-ADMIN01", Role.ADMIN);
        }
        if (demoEnabled) {
            UserAccount demo = ensureAccount(demoEmail, demoPassword, "Demo Customer", "+254700000007",
                    "C-DEMO01", Role.CUSTOMER);
            ensureDemoCashLoan(demo);
        }
    }

    private UserAccount ensureAccount(String email, String rawPassword, String fullName,
                                      String phoneNumber, String customerCode, Role role) {
        return userAccountRepository.findByEmail(email).orElseGet(() -> createBootstrapAccount(
                email, rawPassword, fullName, phoneNumber, customerCode, role));
    }

    private UserAccount createBootstrapAccount(String email, String rawPassword, String fullName,
                                               String phoneNumber, String customerCode, Role role) {
        if (rawPassword == null || rawPassword.isBlank()) {
            log.warn("Skipping bootstrap account {}: no password configured.", email);
            return null;
        }
        UserAccount account = new UserAccount();
        account.setFullName(fullName);
        account.setEmail(email);
        account.setPhoneNumber(phoneNumber);
        account.setPassword(passwordEncoder.encode(rawPassword));
        account.setCustomerCode(resolveCustomerCode(customerCode));
        account.setRole(role);
        UserAccount saved = userAccountRepository.save(account);
        log.info("Bootstrap {} account ready: email={}", role, email);
        return saved;
    }

    private String resolveCustomerCode(String preferred) {
        if (!userAccountRepository.existsByCustomerCode(preferred)) {
            return preferred;
        }
        return preferred + "-" + System.currentTimeMillis() % 1000;
    }

    private void ensureDemoCashLoan(UserAccount demo) {
        if (demo == null || !cashLoanRepository.findAllByCustomerId(demo.getId()).isEmpty()) {
            return;
        }
        CashLoan loan = new CashLoan();
        loan.setCustomer(demo);
        loan.setAmountRequested(new BigDecimal("25000.00"));
        loan.setApprovedAmount(new BigDecimal("25000.00"));
        loan.setBalanceRemaining(new BigDecimal("15000.00"));
        loan.setMonthlyRate(new BigDecimal("4.00"));
        loan.setPurpose("School fees");
        loan.setStatus("APPROVED");
        cashLoanRepository.save(loan);
    }
}
