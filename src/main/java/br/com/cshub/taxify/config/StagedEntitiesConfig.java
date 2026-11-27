package br.com.cshub.taxify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.persistenceunit.ManagedClassNameFilter;

/**
 * Keeps the new fiscal document model ({@code domain/fiscaldocument}, spec 2026-11-27-remodelagem-documentos-fiscais)
 * out of the JPA persistence unit while its tables do not exist: with {@code ddl-auto: validate}, an entity without a
 * table stops the application. Spring Boot hands this filter to the entity scan (the {@code PersistenceManagedTypes}
 * auto-configuration takes any {@link ManagedClassNameFilter} bean), so nothing else of the JPA setup changes.
 *
 * <p>Remove this class in the same commit that adds the V86 migration (B-FD-02), which creates the tables.
 */
@Configuration(proxyBeanMethods = false)
public class StagedEntitiesConfig {

    /** Package of the entities that are not in the persistence unit yet. */
    public static final String STAGED_PACKAGE = "br.com.cshub.taxify.domain.fiscaldocument.";

    @Bean
    public ManagedClassNameFilter stagedEntitiesFilter() {
        return StagedEntitiesConfig::isManaged;
    }

    /** Whether the class (entity or converter) is part of the persistence unit now. */
    public static boolean isManaged(String className) {
        return !className.startsWith(STAGED_PACKAGE);
    }
}
