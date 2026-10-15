package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.CashLoan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CashLoanRepository extends JpaRepository<CashLoan, Long> {
    List<CashLoan> findAllByCustomerId(Long customerId);
}
