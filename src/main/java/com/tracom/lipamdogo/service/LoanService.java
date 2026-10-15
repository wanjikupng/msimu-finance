package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.LoanContractResponse;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Contract reads. All financial state changes flow exclusively through the
 * payment engine (PaymentService → PaymentPostingService); this service
 * performs no balance arithmetic.
 */
@Service
public class LoanService {

    private final LoanContractRepository loanContractRepository;

    public LoanService(LoanContractRepository loanContractRepository) {
        this.loanContractRepository = loanContractRepository;
    }

    @Transactional(readOnly = true)
    public List<LoanContractResponse> getMyContracts(UserPrincipal principal) {
        return loanContractRepository.findAllByCustomerId(principal.getId()).stream()
                .map(LoanContractResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public LoanContractResponse getContract(UserPrincipal principal, Long contractId) {
        LoanContract contract = loanContractRepository.findById(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Financing contract not found."));
        if (!contract.getCustomer().getId().equals(principal.getId()) && !principal.isStaff()) {
            throw new AccessDeniedException("You may only view your own financing contracts.");
        }
        return LoanContractResponse.from(contract);
    }

    @Transactional(readOnly = true)
    public List<LoanContractResponse> getAllContracts() {
        return loanContractRepository.findAll().stream()
                .map(LoanContractResponse::from)
                .toList();
    }
}
