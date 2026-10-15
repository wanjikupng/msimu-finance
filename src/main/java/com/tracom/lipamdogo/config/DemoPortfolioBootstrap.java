package com.tracom.lipamdogo.config;

import com.tracom.lipamdogo.dto.AdminUserRequest;
import com.tracom.lipamdogo.dto.DeviceRequest;
import com.tracom.lipamdogo.dto.FinancingApplicationRequest;
import com.tracom.lipamdogo.dto.PaymentInitiateRequest;
import com.tracom.lipamdogo.dto.PosCollectRequest;
import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.PaymentPlan;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.payment.MockProviderScenario;
import com.tracom.lipamdogo.payment.ProviderStatementSimulator;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.repository.FinancingApplicationRepository;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentPlanRepository;
import com.tracom.lipamdogo.repository.ProductRepository;
import com.tracom.lipamdogo.repository.TerminalRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.service.AdminService;
import com.tracom.lipamdogo.service.ArrearsService;
import com.tracom.lipamdogo.service.DeviceService;
import com.tracom.lipamdogo.service.FinancingService;
import com.tracom.lipamdogo.service.PaymentService;
import com.tracom.lipamdogo.service.PosService;
import com.tracom.lipamdogo.service.ReconciliationService;
import com.tracom.lipamdogo.support.SystemTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Demo portfolio: a realistic 40-day book of business so every dashboard has
 * something true to show on a fresh start.
 *
 * <p>Nothing here writes balances directly. Each customer applies, is
 * reviewed and approved, and pays through the real services, with
 * {@link SystemTime#runAt} pinning "now" to the historical day of each step.
 * Contracts, allocations, receipts, device states and reconciliation results
 * are therefore exactly what the engine would have produced had the events
 * happened on those days.</p>
 *
 * <p>Enabled by {@code app.demo.portfolio.enabled} (on in the academy/demo
 * configuration, off in tests) and only when no financing exists yet.</p>
 */
@Component
@Order(3)
public class DemoPortfolioBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoPortfolioBootstrap.class);
    private static final String STAFF_PASSWORD = "Staff@1234";
    private static final String CUSTOMER_PASSWORD = "Demo@1234";

    private final boolean enabled;
    private final UserAccountRepository userAccountRepository;
    private final LoanContractRepository loanContractRepository;
    private final FinancingApplicationRepository applicationRepository;
    private final DeviceRepository deviceRepository;
    private final ProductRepository productRepository;
    private final PaymentPlanRepository planRepository;
    private final AdminService adminService;
    private final DeviceService deviceService;
    private final FinancingService financingService;
    private final PaymentService paymentService;
    private final ArrearsService arrearsService;
    private final ProviderStatementSimulator statementSimulator;
    private final ReconciliationService reconciliationService;
    private final TerminalRepository terminalRepository;
    private final PosService posService;

    private UserPrincipal officer;
    private int imeiSequence = 0;

    public DemoPortfolioBootstrap(@Value("${app.demo.portfolio.enabled:false}") boolean enabled,
                                  UserAccountRepository userAccountRepository,
                                  LoanContractRepository loanContractRepository,
                                  FinancingApplicationRepository applicationRepository,
                                  DeviceRepository deviceRepository,
                                  ProductRepository productRepository,
                                  PaymentPlanRepository planRepository,
                                  AdminService adminService,
                                  DeviceService deviceService,
                                  FinancingService financingService,
                                  PaymentService paymentService,
                                  ArrearsService arrearsService,
                                  ProviderStatementSimulator statementSimulator,
                                  ReconciliationService reconciliationService,
                                  TerminalRepository terminalRepository,
                                  PosService posService) {
        this.enabled = enabled;
        this.userAccountRepository = userAccountRepository;
        this.loanContractRepository = loanContractRepository;
        this.applicationRepository = applicationRepository;
        this.deviceRepository = deviceRepository;
        this.productRepository = productRepository;
        this.planRepository = planRepository;
        this.adminService = adminService;
        this.deviceService = deviceService;
        this.financingService = financingService;
        this.paymentService = paymentService;
        this.arrearsService = arrearsService;
        this.statementSimulator = statementSimulator;
        this.reconciliationService = reconciliationService;
        this.terminalRepository = terminalRepository;
        this.posService = posService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || loanContractRepository.count() > 0 || applicationRepository.count() > 0) {
            return;
        }
        LocalDate today = SystemTime.today();

        officer = new UserPrincipal(staff("Grace Wambui", "loan.officer@tracom.local", "+254700000021",
                Role.LOAN_OFFICER));
        staff("Daniel Kiprop", "payments@tracom.local", "+254700000022", Role.PAYMENTS_OPERATOR);

        // 1. Demo customer: a steady daily payer on a Galaxy A15 (Flex 200,
        //    KES 70/day). Missed one day and caught up the next.
        UserAccount demo = userAccountRepository.findByEmail("demo@lipamdogo.com").orElseThrow();
        Long demoContract = finance(demo, existingDevice("TRC-000131"), "Flex 200 Days", "4000", today.minusDays(21));
        payDaily(demo, demoContract, today.minusDays(20), today.minusDays(1), "70", day -> day != 9);
        pay(demo, demoContract, "70", at(today.minusDays(11), 19, 40), MockProviderScenario.SUCCESS);

        // 2. Wanjiru: pays three days at a time at QuickMart, where agent Amina
        //    sends the M-PESA request from terminal TRM-0001 and Wanjiru approves
        //    it on her phone. Today she started a payment in the app that is
        //    still waiting for her PIN.
        UserAccount wanjiru = customer("Wanjiru Kamau", "wanjiru@demo.msimu", "+254711000201");
        Long wanjiruContract = finance(wanjiru, newDevice("Tecno Camon 20"), "Standard 120 Days", "5000",
                today.minusDays(30));
        for (int d = 29; d >= 2; d -= 3) {
            payAtTerminal(wanjiruContract, "426", at(today.minusDays(d), 12, 15), "TRM-0001", true);
        }
        pay(wanjiru, wanjiruContract, "426", at(today, 9, 5), MockProviderScenario.PENDING);

        // 3. Otieno: paid for two weeks, then stopped; a failed attempt five
        //    days ago. Now 8+ days in arrears, device OVERDUE.
        UserAccount otieno = customer("Otieno Ouma", "otieno@demo.msimu", "+254722000301");
        Long otienoContract = finance(otieno, newDevice("Redmi 14C"), "Fast 90 Days", "3000", today.minusDays(25));
        payDaily(otieno, otienoContract, today.minusDays(24), today.minusDays(11), "134", day -> true);
        pay(otieno, otienoContract, "134", at(today.minusDays(5), 20, 2), MockProviderScenario.FAILED);

        // 4. Achieng: recent customer, missed the last few days (1-7 bucket).
        UserAccount achieng = customer("Achieng Atieno", "achieng@demo.msimu", "+254733000401");
        Long achiengContract = finance(achieng, newDevice("Infinix Hot 40"), "Flex 200 Days", "3500",
                today.minusDays(14));
        payDaily(achieng, achiengContract, today.minusDays(13), today.minusDays(4), "63", day -> true);
        // This morning agent Faith (TechPoint, TRM-0003) sent her a request; she declined it.
        payAtTerminal(achiengContract, "189", at(today, 8, 40), "TRM-0003", false);

        // 5. Kiprono: large deposit, paid in three lump sums. PAID_OFF.
        UserAccount kiprono = customer("Kiprono Ruto", "kiprono@demo.msimu", "+254744000501");
        Long kipronoContract = finance(kiprono, newDevice("Tecno Spark 10"), "Fast 90 Days", "10000",
                today.minusDays(40));
        pay(kiprono, kipronoContract, "1500", at(today.minusDays(35), 10, 30), MockProviderScenario.SUCCESS);
        pay(kiprono, kipronoContract, "1500", at(today.minusDays(20), 10, 30), MockProviderScenario.SUCCESS);
        pay(kiprono, kipronoContract, "1000", at(today.minusDays(6), 16, 45), MockProviderScenario.SUCCESS);

        // 6-8. The application queue: new, under review, and a rejection.
        UserAccount mutua = customer("Mutua Nzioka", "mutua@demo.msimu", "+254755000601");
        submit(mutua, newDevice("Samsung Galaxy A15"), "Standard 120 Days", "4000", at(today, 8, 20));
        UserAccount halima = customer("Halima Abdi", "halima@demo.msimu", "+254766000701");
        Long halimaApp = submit(halima, newDevice("Redmi 14C"), "Flex 200 Days", "3000",
                at(today.minusDays(1), 15, 10));
        SystemTime.runAt(at(today, 9, 0), () -> financingService.startReview(officer, halimaApp));
        UserAccount peter = customer("Peter Kamau", "peter@demo.msimu", "+254777000801");
        Long peterApp = submit(peter, newDevice("Tecno Camon 20"), "Fast 90 Days", "5000",
                at(today.minusDays(3), 11, 0));
        SystemTime.runAt(at(today.minusDays(2), 10, 0), () -> {
            financingService.startReview(officer, peterApp);
            return financingService.rejectApplication(officer, peterApp,
                    "National ID could not be verified against the application details.");
        });

        // Evidence and controls: today's end-of-day sweep, the provider's
        // statement (with two sample exceptions) and a reconciliation run.
        arrearsService.runSweep();
        statementSimulator.importMpesaStatement(true);
        reconciliationService.run(null, null, null);

        // Terminal heartbeats: the three agents' terminals checked in at start-up;
        // the faulty one has been silent for over a week.
        for (String code : new String[]{"TRM-0001", "TRM-0002", "TRM-0003"}) {
            terminalRepository.findByTerminalCode(code).ifPresent(t -> {
                t.setLastSeenAt(SystemTime.now());
                terminalRepository.save(t);
            });
        }
        terminalRepository.findByTerminalCode("TRM-0005").ifPresent(t -> {
            t.setLastSeenAt(SystemTime.now().minusDays(9));
            terminalRepository.save(t);
        });

        log.info("Demo portfolio ready: 8 customers, 5 contracts, 3 further applications, 2 staff users, agent terminal collections.");
    }

    private UserAccount staff(String name, String email, String phone, Role role) {
        return userAccountRepository.findByEmail(email).orElseGet(() -> {
            adminService.createUser(new AdminUserRequest(name, email, phone, STAFF_PASSWORD, role));
            return userAccountRepository.findByEmail(email).orElseThrow();
        });
    }

    private UserAccount customer(String name, String email, String phone) {
        return userAccountRepository.findByEmail(email).orElseGet(() -> {
            adminService.createUser(new AdminUserRequest(name, email, phone, CUSTOMER_PASSWORD, Role.CUSTOMER));
            return userAccountRepository.findByEmail(email).orElseThrow();
        });
    }

    private Long existingDevice(String code) {
        return deviceRepository.findByDeviceCode(code).orElseThrow().getId();
    }

    private Long newDevice(String productName) {
        Long productId = productRepository.findByName(productName).orElseThrow().getId();
        imeiSequence++;
        String imei = "35%013d".formatted(4_402_000_000L + imeiSequence);
        String serial = "DEMO-%04d".formatted(imeiSequence);
        return deviceService.createDevice(new DeviceRequest(productId, imei, serial)).id();
    }

    private Long planId(String name) {
        return planRepository.findAll().stream()
                .filter(p -> p.getPlanName().equals(name))
                .map(PaymentPlan::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Seed plan missing: " + name));
    }

    private Long submit(UserAccount customer, Long deviceId, String plan, String deposit, LocalDateTime when) {
        return SystemTime.runAt(when, () -> financingService.submitApplication(new UserPrincipal(customer),
                new FinancingApplicationRequest(deviceId, new BigDecimal(deposit), planId(plan), null)).id());
    }

    /** Applies in the morning and is approved the same afternoon. Returns the contract id. */
    private Long finance(UserAccount customer, Long deviceId, String plan, String deposit, LocalDate day) {
        Long applicationId = submit(customer, deviceId, plan, deposit, at(day, 9, 30));
        return SystemTime.runAt(at(day, 14, 0), () -> {
            financingService.startReview(officer, applicationId);
            return financingService.approveApplication(officer, applicationId).id();
        });
    }

    private void payDaily(UserAccount customer, Long contractId, LocalDate from, LocalDate to, String amount,
                          IntPredicate paysOnDay) {
        int dayNumber = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            dayNumber++;
            if (paysOnDay.test(dayNumber)) {
                pay(customer, contractId, amount, at(day, 7 + dayNumber % 12, (dayNumber * 7) % 60),
                        MockProviderScenario.SUCCESS);
            }
        }
    }

    private void pay(UserAccount customer, Long contractId, String amount, LocalDateTime when,
                     MockProviderScenario scenario) {
        SystemTime.runAt(when, () -> paymentService.initiate(new UserPrincipal(customer),
                new PaymentInitiateRequest(contractId, new BigDecimal(amount), PaymentChannel.MPESA, scenario),
                UUID.randomUUID().toString()));
    }

    /**
     * The terminal's agent sends an M-PESA request through the same service the
     * POS app uses; the customer answers on their phone a minute later.
     */
    private void payAtTerminal(Long contractId, String amount, LocalDateTime when, String terminalCode,
                               boolean customerApproves) {
        String agentEmail = Map.of("TRM-0001", "amina.agent@tracom.local",
                "TRM-0002", "brian.agent@tracom.local",
                "TRM-0003", "faith.agent@tracom.local").get(terminalCode);
        UserPrincipal agent = new UserPrincipal(userAccountRepository.findByEmail(agentEmail).orElseThrow());
        String reference = SystemTime.runAt(when, () -> posService.collect(terminalCode, agent,
                new PosCollectRequest(contractId, new BigDecimal(amount), null), UUID.randomUUID().toString())
                .transactionReference());
        SystemTime.runAt(when.plusMinutes(1), () ->
                posService.simulateCustomerResponse(terminalCode, agent, reference, customerApproves));
    }

    private static LocalDateTime at(LocalDate day, int hour, int minute) {
        return LocalDateTime.of(day, LocalTime.of(hour, minute));
    }
}
