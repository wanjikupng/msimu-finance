package com.tracom.lipamdogo.repository;

import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByCustomerCode(String customerCode);

    List<UserAccount> findByRoleOrderByCreatedAtDesc(Role role);
}
