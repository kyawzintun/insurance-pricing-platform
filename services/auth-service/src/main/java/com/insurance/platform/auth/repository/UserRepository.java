package com.insurance.platform.auth.repository;

import com.insurance.platform.auth.entity.User;

import java.util.Optional;
import java.util.UUID;

import com.insurance.platform.auth.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {
    boolean existsByEmailIgnoreCase(String email);
    Optional<User> findByEmailIgnoreCase(String email);

    Page<User> findByRole(Role role, Pageable pageable);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}
