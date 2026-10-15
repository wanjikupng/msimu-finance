package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.ArrearsSweepResponse;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.support.SystemTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;

/**
 * End-of-day arrears sweep.
 *
 * <p>Contract status is otherwise only recomputed when money moves (a posting
 * or a reversal). A customer who simply stops paying never triggers either, so
 * without this sweep their contract would stay ACTIVE forever and the
 * portfolio-at-risk figures would always read zero. The sweep re-evaluates
 * every live contract against today's date using the same rule as posting
 * ({@link PaymentPostingService#refreshLoanState}), which also moves the
 * device between FINANCED and OVERDUE.</p>
 *
 * <p>It never changes balances, allocations or due dates — only the derived
 * status. Runs nightly and on demand from the staff console.</p>
 */
@Service
public class ArrearsService {

    private static final Logger log = LoggerFactory.getLogger(ArrearsService.class);

    private final LoanContractRepository loanContractRepository;
    private final PaymentPostingService postingService;

    public ArrearsService(LoanContractRepository loanContractRepository, PaymentPostingService postingService) {
        this.loanContractRepository = loanContractRepository;
        this.postingService = postingService;
    }

    @Transactional
    public ArrearsSweepResponse runSweep() {
        List<Long> ids = loanContractRepository.findIdsByStatusIn(EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE));
        int newlyOverdue = 0;
        int backToActive = 0;
        for (Long id : ids) {
            LoanContract contract = loanContractRepository.findByIdForUpdate(id).orElse(null);
            if (contract == null) {
                continue;
            }
            LoanStatus before = contract.getStatus();
            postingService.refreshLoanState(contract);
            loanContractRepository.save(contract);
            if (before != LoanStatus.OVERDUE && contract.getStatus() == LoanStatus.OVERDUE) {
                newlyOverdue++;
            } else if (before == LoanStatus.OVERDUE && contract.getStatus() == LoanStatus.ACTIVE) {
                backToActive++;
            }
        }
        int overdueTotal = (int) loanContractRepository.countByStatus(LoanStatus.OVERDUE);
        ArrearsSweepResponse result = new ArrearsSweepResponse(SystemTime.today(), ids.size(),
                newlyOverdue, backToActive, overdueTotal, SystemTime.now());
        log.info("Arrears sweep {}: checked {}, newly overdue {}, back to active {}, overdue total {}.",
                result.businessDate(), result.contractsChecked(), newlyOverdue, backToActive, overdueTotal);
        return result;
    }

    @Scheduled(cron = "${app.arrears.sweep-cron:0 5 0 * * *}")
    public void nightlySweep() {
        runSweep();
    }
}
