package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.UnlockCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UnlockCodeRepository extends JpaRepository<UnlockCode, Long> {

    Optional<UnlockCode> findFirstByLockKeyIdOrderBySequenceDesc(Long lockKeyId);

    List<UnlockCode> findAllByLockKeyIdOrderBySequenceDesc(Long lockKeyId);
}
