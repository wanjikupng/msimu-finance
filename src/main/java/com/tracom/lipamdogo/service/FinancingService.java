package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.FinancingApplicationRequest;
import com.tracom.lipamdogo.dto.FinancingApplicationResponse;
import com.tracom.lipamdogo.dto.FinancingQuoteResponse;
import com.tracom.lipamdogo.dto.FinancingSummaryResponse;
import com.tracom.lipamdogo.dto.LoanContractResponse;
import com.tracom.lipamdogo.model.ApplicationStatus;
import com.tracom.lipamdogo.model.Device;
import com.tracom.lipamdogo.model.DeviceStatus;
import com.tracom.lipamdogo.model.FinancingApplication;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.model.PaymentMode;
import com.tracom.lipamdogo.model.PaymentPlan;
import com.tracom.lipamdogo.model.Product;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.repository.FinancingApplicationRepository;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentModeRepository;
import com.tracom.lipamdogo.repository.PaymentPlanRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.support.SystemTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The financing workflow. Application approval is the ONLY path to a live
 * contract. Every multi-step decision (submit/reserve, approve/contract/
 * handover) runs in one transaction with write locks on the application and
 * device rows, so concurrent officers or customers cannot double-commit the
 * same physical device.
 */
@Service
public class FinancingService {

    private static final Set<ApplicationStatus> BLOCKING_STATUSES = EnumSet.of(
            ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.APPROVED);

    private static final Set<LoanStatus> LIVE_CONTRACT_STATUSES = EnumSet.of(
            LoanStatus.ACTIVE, LoanStatus.OVERDUE);

    private final FinancingApplicationRepository applicationRepository;
    private final LoanContractRepository loanContractRepository;
    private final DeviceRepository deviceRepository;
    private final PaymentPlanRepository paymentPlanRepository;
    private final PaymentModeRepository paymentModeRepository;
    private final UserAccountRepository userAccountRepository;
    private final DeviceService deviceService;
    private final FinancingCalculationService calculationService;

    public FinancingService(FinancingApplicationRepository applicationRepository,
                            LoanContractRepository loanContractRepository,
                            DeviceRepository deviceRepository,
                            PaymentPlanRepository paymentPlanRepository,
                            PaymentModeRepository paymentModeRepository,
                            UserAccountRepository userAccountRepository,
                            DeviceService deviceService,
                            FinancingCalculationService calculationService) {
        this.applicationRepository = applicationRepository;
        this.loanContractRepository = loanContractRepository;
        this.deviceRepository = deviceRepository;
        this.paymentPlanRepository = paymentPlanRepository;
        this.paymentModeRepository = paymentModeRepository;
        this.userAccountRepository = userAccountRepository;
        this.deviceService = deviceService;
        this.calculationService = calculationService;
    }

    /**
     * Customer submits financing for one AVAILABLE device. The device row is
     * write-locked first, so two customers racing for the same unit serialize:
     * exactly one reservation wins.
     */
    @Transactional
    public FinancingApplicationResponse submitApplication(UserPrincipal principal,
                                                          FinancingApplicationRequest request) {
        UserAccount customer = requireAccount(principal.getId());

        Device device = deviceRepository.findByIdForUpdate(request.deviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));
        if (device.getStatus() != DeviceStatus.AVAILABLE) {
            throw new IllegalArgumentException("Only AVAILABLE devices can be financed.");
        }
        if (!applicationRepository.findBlockingByDeviceId(device.getId(), BLOCKING_STATUSES).isEmpty()) {
            throw new IllegalArgumentException("This device already has an active financing application.");
        }
        if (!loanContractRepository.findLiveByDeviceId(device.getId(), LIVE_CONTRACT_STATUSES).isEmpty()) {
            throw new IllegalArgumentException("This device already has active financing.");
        }

        PaymentPlan plan = paymentPlanRepository.findById(request.paymentPlanId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment plan not found with id: " + request.paymentPlanId()));
        PaymentMode mode = null;
        if (request.paymentModeId() != null) {
            mode = paymentModeRepository.findById(request.paymentModeId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Payment mode not found with id: " + request.paymentModeId()));
        }

        FinancingCalculationService.FinancingTerms terms =
                calculationService.calculate(device.getProduct(), plan, request.depositAmount());

        FinancingApplication application = new FinancingApplication();
        application.setCustomer(customer);
        application.setDevice(device);
        application.setStatus(ApplicationStatus.SUBMITTED);
        application.setPaymentPlan(plan);
        application.setPaymentMode(mode);
        application.setDevicePrice(terms.devicePrice());
        application.setDepositAmount(terms.depositAmount());
        application.setRequestedPrincipal(terms.requestedPrincipal());
        application.setInstallmentAmount(terms.installmentAmount());

        FinancingApplication saved = applicationRepository.saveAndFlush(application);
        saved.setApplicationNumber(number("APP", saved.getId()));
        applicationRepository.save(saved);

        deviceService.reserveForFinancing(device.getId());

        return FinancingApplicationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public FinancingQuoteResponse quote(Long deviceId, Long paymentPlanId, java.math.BigDecimal deposit) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));
        PaymentPlan plan = paymentPlanRepository.findById(paymentPlanId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment plan not found with id: " + paymentPlanId));
        FinancingCalculationService.FinancingTerms terms =
                calculationService.calculate(device.getProduct(), plan, deposit);
        return new FinancingQuoteResponse(
                device.getId(),
                device.getDeviceCode(),
                device.getProduct().getName(),
                terms.devicePrice(),
                terms.depositAmount(),
                terms.requestedPrincipal(),
                terms.installmentAmount(),
                terms.paymentFrequency(),
                terms.paymentPlanName(),
                terms.planDurationDays(),
                terms.totalPayable()
        );
    }

    @Transactional(readOnly = true)
    public List<FinancingApplicationResponse> myApplications(UserPrincipal principal) {
        return applicationRepository.findAllByCustomerId(principal.getId()).stream()
                .map(FinancingApplicationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public FinancingApplicationResponse myApplication(UserPrincipal principal, Long id) {
        return FinancingApplicationResponse.from(requireOwnApplication(principal.getId(), id));
    }

    /**
     * Customer withdraws an undecided application; the reservation is released
     * back through the device lifecycle.
     */
    @Transactional
    public FinancingApplicationResponse cancelApplication(UserPrincipal principal, Long id) {
        // Scoped lookup first: another customer's application is
        // indistinguishable from a missing one (404), matching myApplication.
        requireOwnApplication(principal.getId(), id);
        FinancingApplication application = applicationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));
        if (application.getStatus() != ApplicationStatus.SUBMITTED
                && application.getStatus() != ApplicationStatus.UNDER_REVIEW) {
            throw new IllegalArgumentException("Only undecided applications can be cancelled.");
        }
        application.setStatus(ApplicationStatus.CANCELLED);
        application.setUpdatedAt(SystemTime.now());
        deviceService.releaseReservation(application.getDevice().getId());
        return FinancingApplicationResponse.from(applicationRepository.save(application));
    }

    @Transactional(readOnly = true)
    public List<FinancingApplicationResponse> listApplications(ApplicationStatus status) {
        List<FinancingApplication> applications = status == null
                ? applicationRepository.findAll()
                : applicationRepository.findAllByStatus(status);
        return applications.stream().map(FinancingApplicationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public FinancingApplicationResponse getApplication(Long id) {
        return FinancingApplicationResponse.from(requireApplication(id));
    }

    @Transactional
    public FinancingApplicationResponse startReview(UserPrincipal reviewer, Long id) {
        FinancingApplication application = applicationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));
        if (application.getStatus() != ApplicationStatus.SUBMITTED) {
            throw new IllegalArgumentException("Only SUBMITTED applications can be taken into review.");
        }
        application.setStatus(ApplicationStatus.UNDER_REVIEW);
        application.setUpdatedAt(SystemTime.now());
        return FinancingApplicationResponse.from(applicationRepository.save(application));
    }

    /**
     * Approval is one atomic transaction: lock application → lock device →
     * re-check device state and live financing → create contract from the
     * locked application terms → hand the device to the customer. Any failure
     * rolls everything back; a second concurrent approval finds the device no
     * longer RESERVED and fails instead of double-financing it.
     */
    @Transactional
    public LoanContractResponse approveApplication(UserPrincipal reviewer, Long id) {
        FinancingApplication application = applicationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));
        if (application.getStatus() != ApplicationStatus.UNDER_REVIEW) {
            throw new IllegalArgumentException("Only UNDER_REVIEW applications can be approved.");
        }

        Device device = deviceRepository.findByIdForUpdate(application.getDevice().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));
        if (device.getStatus() != DeviceStatus.RESERVED && device.getStatus() != DeviceStatus.ASSIGNED) {
            throw new IllegalArgumentException(
                    "Device is no longer reserved for this application (current state: " + device.getStatus() + ").");
        }
        if (!loanContractRepository.findLiveByDeviceId(device.getId(), LIVE_CONTRACT_STATUSES).isEmpty()) {
            throw new IllegalArgumentException("This device already has active financing.");
        }

        UserAccount reviewerAccount = requireAccount(reviewer.getId());
        Product product = device.getProduct();
        PaymentPlan plan = application.getPaymentPlan();

        LoanContract contract = new LoanContract();
        contract.setCustomer(application.getCustomer());
        contract.setProduct(product);
        contract.setDevice(device);
        contract.setApplication(application);
        contract.setDepositPaid(application.getDepositAmount());
        // Terms come from the application snapshot, never the live plan: the
        // customer is approved on exactly what they were quoted.
        contract.setDailyAmount(application.getInstallmentAmount() != null
                ? application.getInstallmentAmount()
                : application.getRequestedPrincipal().divide(BigDecimal.valueOf(plan.getDurationDays()),
                        0, java.math.RoundingMode.CEILING));
        contract.setAmountRemaining(application.getRequestedPrincipal());
        contract.setPrincipalAmount(application.getRequestedPrincipal());
        contract.setTotalPayable(application.getRequestedPrincipal());
        contract.setPaymentFrequency(com.tracom.lipamdogo.model.PaymentFrequency.DAILY);
        contract.setDevicePrice(application.getDevicePrice());
        contract.setPlanName(plan.getPlanName());
        contract.setPlanDurationDays(plan.getDurationDays());
        contract.setStatus(LoanStatus.ACTIVE);
        contract.setNextPaymentDueDate(SystemTime.today().plusDays(1));
        contract.setScheduleAnchorDate(contract.getNextPaymentDueDate());
        contract.setCreatedAt(SystemTime.now());

        LoanContract saved = loanContractRepository.saveAndFlush(contract);
        saved.setContractNumber(number("LN", saved.getId()));
        loanContractRepository.save(saved);

        deviceService.handoverToCustomer(device.getId(), application.getCustomer().getId());

        application.setStatus(ApplicationStatus.APPROVED);
        application.setApprovedPrincipal(application.getRequestedPrincipal());
        application.setReviewedBy(reviewerAccount);
        application.setReviewedAt(SystemTime.now());
        application.setUpdatedAt(SystemTime.now());
        applicationRepository.save(application);

        return LoanContractResponse.from(saved);
    }

    @Transactional
    public FinancingApplicationResponse rejectApplication(UserPrincipal reviewer, Long id, String decisionReason) {
        if (decisionReason == null || decisionReason.isBlank()) {
            throw new IllegalArgumentException("A decision reason is required.");
        }
        FinancingApplication application = applicationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));
        if (application.getStatus() != ApplicationStatus.SUBMITTED
                && application.getStatus() != ApplicationStatus.UNDER_REVIEW) {
            throw new IllegalArgumentException("Only undecided applications can be rejected.");
        }
        application.setStatus(ApplicationStatus.REJECTED);
        application.setDecisionReason(decisionReason.trim());
        application.setReviewedBy(requireAccount(reviewer.getId()));
        application.setReviewedAt(SystemTime.now());
        application.setUpdatedAt(SystemTime.now());
        applicationRepository.save(application);

        Device device = deviceRepository.findById(application.getDevice().getId()).orElse(null);
        if (device != null && device.getStatus() == DeviceStatus.RESERVED) {
            deviceService.releaseReservation(device.getId());
        }
        return FinancingApplicationResponse.from(application);
    }

    @Transactional(readOnly = true)
    public FinancingSummaryResponse summary() {
        long activeContracts = loanContractRepository.countByStatus(LoanStatus.ACTIVE)
                + loanContractRepository.countByStatus(LoanStatus.OVERDUE);
        return new FinancingSummaryResponse(
                applicationRepository.count(),
                applicationRepository.countByStatus(ApplicationStatus.SUBMITTED),
                applicationRepository.countByStatus(ApplicationStatus.UNDER_REVIEW),
                applicationRepository.countByStatus(ApplicationStatus.APPROVED),
                applicationRepository.countByStatus(ApplicationStatus.REJECTED),
                applicationRepository.countByStatus(ApplicationStatus.CANCELLED),
                activeContracts
        );
    }

    private FinancingApplication requireApplication(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));
    }

    private FinancingApplication requireOwnApplication(Long customerId, Long id) {
        return applicationRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."));
    }

    private UserAccount requireAccount(Long userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer account not found."));
    }

    private String number(String prefix, Long id) {
        return "%s-%d-%06d".formatted(prefix, SystemTime.today().getYear(), id);
    }
}
