package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.model.MerchantStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    Optional<Merchant> findByMerchantCode(String merchantCode);

    boolean existsByMerchantCode(String merchantCode);

    List<Merchant> findAllByStatus(MerchantStatus status);

    List<Merchant> findAllByOwnerId(Long ownerId);
}
