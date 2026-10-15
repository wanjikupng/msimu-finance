package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.PaymentChannel;
import com.tracom.lipamdogo.model.ProviderTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProviderTransactionRepository extends JpaRepository<ProviderTransaction, Long> {

    List<ProviderTransaction> findByProviderAndProviderReference(PaymentChannel provider, String providerReference);

    Optional<ProviderTransaction> findFirstByProviderAndProviderReferenceAndAmountAndTransactionTime(
            PaymentChannel provider, String providerReference, java.math.BigDecimal amount, LocalDateTime transactionTime);

    List<ProviderTransaction> findAllByProvider(PaymentChannel provider);

    @Query("select p from ProviderTransaction p where (:provider is null or p.provider = :provider)"
            + " and (:start is null or p.transactionTime >= :start)"
            + " and (:end is null or p.transactionTime <= :end)"
            + " order by p.transactionTime asc, p.id asc")
    List<ProviderTransaction> findInScope(PaymentChannel provider, LocalDateTime start, LocalDateTime end);
}
