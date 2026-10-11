package com.fivegoodmins.fivegoodmins.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * The caller lowercases first. ck_app_user_email_lowercase guarantees every
     * stored address is already lowercase, so a plain equality match is both
     * case-insensitive in effect and able to use uq_app_user_email.
     */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
