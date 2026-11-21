package br.com.cshub.taxify.config;

import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.AccessProfile;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;

/**
 * One-off, on the first startup with access profiles: before them, every non-ADMIN user
 * worked the fiscal side (notes, SIEG, audit, fiscal rules) - so each one gets FISCAL and
 * nobody loses access on deploy day. Runs only while no user has any profile yet; after
 * that, profiles are managed on the users screen.
 */
@Component
public class AccessProfileMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccessProfileMigration.class);
    static final AccessProfile LEGACY_PROFILE = AccessProfile.FISCAL;

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    public AccessProfileMigration(JdbcTemplate jdbcTemplate, UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer assigned = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_access_profiles", Integer.class);
        if (assigned != null && assigned > 0) {
            return;
        }
        List<User> legacy = userRepository.findAll().stream()
                .filter(user -> !user.isDeleted() && user.getProfile() != UserProfileEnum.ADMIN)
                .toList();
        legacy.forEach(user -> user.setAccessProfiles(EnumSet.of(LEGACY_PROFILE)));
        userRepository.saveAll(legacy);
        if (!legacy.isEmpty()) {
            log.info("Gave the {} access profile to {} existing non-admin users", LEGACY_PROFILE, legacy.size());
        }
    }
}
