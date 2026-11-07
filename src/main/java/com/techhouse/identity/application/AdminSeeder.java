package com.techhouse.identity.application;

import com.techhouse.identity.domain.Store;
import com.techhouse.identity.domain.StoreMember;
import com.techhouse.identity.domain.User;
import com.techhouse.identity.persistence.StoreMemberRepository;
import com.techhouse.identity.persistence.StoreRepository;
import com.techhouse.identity.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Creates the initial OWNER account on startup when ADMIN_EMAIL and ADMIN_PASSWORD are set.
 * The credentials come from the environment (never from source control). Idempotent: an existing
 * account is left untouched, so changing the variables later does not reset a password.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

    private static final UUID DEFAULT_STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String email;

    @Value("${app.admin.password:}")
    private String password;

    @Value("${app.admin.display-name:Admin}")
    private String displayName;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        if (userRepository.existsByEmail(email)) {
            log.info("Admin seeder: user {} already exists, skipping", email);
            return;
        }

        Store store = storeRepository.findById(DEFAULT_STORE_ID).orElse(null);
        if (store == null) {
            log.warn("Admin seeder: default store not found, skipping");
            return;
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setRole("OWNER");
        user.setActive(true);
        // save() may return a merged copy (BaseEntity.version starts at 0), so use its result
        user = userRepository.save(user);

        StoreMember member = new StoreMember();
        member.setStore(store);
        member.setUser(user);
        member.setRole("OWNER");
        storeMemberRepository.save(member);

        log.info("Admin seeder: created OWNER {}", email);
    }
}
