package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.DeviceLockResponse;
import com.tracom.lipamdogo.dto.DeviceProvisioningResponse;
import com.tracom.lipamdogo.dto.UnlockCodeResponse;
import com.tracom.lipamdogo.model.DeviceLockKey;
import com.tracom.lipamdogo.model.DeviceLockState;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.model.PaymentTransaction;
import com.tracom.lipamdogo.model.UnlockCode;
import com.tracom.lipamdogo.model.UnlockCodeReason;
import com.tracom.lipamdogo.repository.DeviceLockKeyRepository;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.UnlockCodeRepository;
import com.tracom.lipamdogo.security.UnlockCodeCodec;
import com.tracom.lipamdogo.security.UserPrincipal;
import com.tracom.lipamdogo.support.SystemTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

/**
 * Pay-as-you-go device lock.
 *
 * <p>Each financed device shares a secret key with the platform (created at
 * approval). Whenever money moves the paid-through date, the platform issues
 * a new signed unlock code saying "unlocked until D". The device verifies the
 * code offline ({@link UnlockCodeCodec}) and locks itself once D has passed.</p>
 *
 * <p>D = next payment due date + grace days. The contract turns OVERDUE the
 * day after a missed installment; the phone only locks once the grace days are
 * used up, and a payment that brings the contract current issues the code that
 * unlocks it. A paid-off contract gets a permanent code.</p>
 *
 * <p>The lock never blocks a payment: issuing codes happens inside the
 * posting transaction but cannot make it fail.</p>
 */
@Service
public class DeviceLockService {

    private static final Logger log = LoggerFactory.getLogger(DeviceLockService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceLockKeyRepository keyRepository;
    private final UnlockCodeRepository codeRepository;
    private final LoanContractRepository contractRepository;
    private final int graceDays;

    public DeviceLockService(DeviceLockKeyRepository keyRepository,
                             UnlockCodeRepository codeRepository,
                             LoanContractRepository contractRepository,
                             @Value("${app.device-lock.grace-days:2}") int graceDays) {
        this.keyRepository = keyRepository;
        this.codeRepository = codeRepository;
        this.contractRepository = contractRepository;
        this.graceDays = Math.max(0, graceDays);
    }

    /**
     * Creates the device key for a newly approved contract and issues its
     * first code. Called from the approval transaction.
     */
    @Transactional
    public void provision(LoanContract contract) {
        if (contract.getDevice() == null || keyRepository.findByContractId(contract.getId()).isPresent()) {
            return;
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        DeviceLockKey key = new DeviceLockKey();
        key.setContract(contract);
        key.setDevice(contract.getDevice());
        key.setSecretKey(HexFormat.of().formatHex(secret));
        key.setAnchorDate(SystemTime.today());
        key.setLastSequence(0);
        keyRepository.save(key);
        issueCode(key, contract, UnlockCodeReason.PROVISIONED, null);
    }

    /**
     * Issues a new code if the contract's paid-through date (or ownership)
     * changed since the last one. Partial payments that do not complete an
     * installment change nothing on the device, so they issue nothing.
     */
    @Transactional
    public void refresh(LoanContract contract, PaymentTransaction cause, boolean reversal) {
        DeviceLockKey key = keyRepository.findByContractIdForUpdate(contract.getId()).orElse(null);
        if (key == null) {
            provision(contract);
            return;
        }
        UnlockCodeReason reason = contract.getStatus() == LoanStatus.PAID_OFF
                ? UnlockCodeReason.PAID_OFF
                : reversal ? UnlockCodeReason.REVERSAL : UnlockCodeReason.PAYMENT;
        issueCode(key, contract, reason, cause);
    }

    @Transactional(readOnly = true)
    public DeviceLockResponse forCustomer(UserPrincipal principal, Long contractId) {
        LoanContract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Financing contract not found."));
        if (!contract.getCustomer().getId().equals(principal.getId()) && !principal.isStaff()) {
            throw new AccessDeniedException("You may only view your own devices.");
        }
        return describe(requireKeyForContract(contractId), false);
    }

    @Transactional(readOnly = true)
    public DeviceLockResponse forStaff(Long deviceId) {
        return describe(requireKeyForDevice(deviceId), true);
    }

    @Transactional(readOnly = true)
    public DeviceProvisioningResponse provisioning(Long deviceId) {
        DeviceLockKey key = requireKeyForDevice(deviceId);
        log.info("Provisioning data for device {} handed out (lock agent / simulator flash).",
                key.getDevice().getDeviceCode());
        return new DeviceProvisioningResponse(
                key.getDevice().getDeviceCode(),
                key.getContract().getContractNumber(),
                key.getContract().getProduct().getName(),
                key.getSecretKey(),
                key.getAnchorDate(),
                graceDays,
                latest(key));
    }

    /** What the device shows today once it holds the latest code. */
    public DeviceLockState stateOf(LoanContract contract) {
        if (contract.getStatus() == LoanStatus.PAID_OFF) {
            return DeviceLockState.OWNED;
        }
        LocalDate today = SystemTime.today();
        LocalDate due = contract.getNextPaymentDueDate();
        if (due == null || !today.isAfter(due)) {
            return DeviceLockState.UNLOCKED;
        }
        return today.isAfter(due.plusDays(graceDays)) ? DeviceLockState.LOCKED : DeviceLockState.GRACE;
    }

    private void issueCode(DeviceLockKey key, LoanContract contract, UnlockCodeReason reason,
                           PaymentTransaction cause) {
        boolean permanent = contract.getStatus() == LoanStatus.PAID_OFF;
        LocalDate validUntil = permanent || contract.getNextPaymentDueDate() == null
                ? null
                : contract.getNextPaymentDueDate().plusDays(graceDays);
        int days = permanent ? UnlockCodeCodec.PERMANENT : daysFromAnchor(key, validUntil);

        UnlockCode previous = codeRepository.findFirstByLockKeyIdOrderBySequenceDesc(key.getId()).orElse(null);
        if (previous != null && previous.isPermanent() == permanent
                && (permanent || daysFromAnchor(key, previous.getValidUntil()) == days)) {
            return;
        }
        if (key.getLastSequence() >= UnlockCodeCodec.MAX_SEQUENCE) {
            log.warn("Device {} has used all {} unlock sequences; re-provision its lock agent.",
                    key.getDevice().getDeviceCode(), UnlockCodeCodec.MAX_SEQUENCE);
            return;
        }
        int sequence = key.getLastSequence() + 1;
        UnlockCode code = new UnlockCode();
        code.setLockKey(key);
        code.setSequence(sequence);
        code.setPermanent(permanent);
        code.setValidUntil(permanent ? null : key.getAnchorDate().plusDays(days));
        code.setCode(UnlockCodeCodec.encode(key.getSecretKey(), key.getDevice().getDeviceCode(), sequence, days));
        code.setReason(reason);
        code.setPaymentTransaction(cause);
        codeRepository.save(code);
        key.setLastSequence(sequence);
        keyRepository.save(key);
    }

    private int daysFromAnchor(DeviceLockKey key, LocalDate validUntil) {
        if (validUntil == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(key.getAnchorDate(), validUntil);
        return (int) Math.max(0, Math.min(UnlockCodeCodec.MAX_DAYS, days));
    }

    private DeviceLockResponse describe(DeviceLockKey key, boolean withHistory) {
        LoanContract contract = key.getContract();
        UnlockCodeResponse latest = latest(key);
        List<UnlockCodeResponse> history = withHistory
                ? codeRepository.findAllByLockKeyIdOrderBySequenceDesc(key.getId()).stream()
                        .map(UnlockCodeResponse::from).toList()
                : List.of();
        return new DeviceLockResponse(
                contract.getId(),
                contract.getContractNumber(),
                key.getDevice().getId(),
                key.getDevice().getDeviceCode(),
                stateOf(contract).name(),
                contract.getNextPaymentDueDate(),
                latest == null ? null : latest.validUntil(),
                graceDays,
                latest,
                history);
    }

    private UnlockCodeResponse latest(DeviceLockKey key) {
        return codeRepository.findFirstByLockKeyIdOrderBySequenceDesc(key.getId())
                .map(UnlockCodeResponse::from).orElse(null);
    }

    private DeviceLockKey requireKeyForContract(Long contractId) {
        return keyRepository.findByContractId(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This contract has no device lock."));
    }

    private DeviceLockKey requireKeyForDevice(Long deviceId) {
        return keyRepository.findFirstByDeviceIdOrderByIdDesc(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This device has no lock key (it has not been financed)."));
    }
}
