package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 3.12 D-01: after V86 every entity of {@code domain/fiscaldocument} is part of the persistence unit the
 * application builds - no staging filter left - and Hibernate validated them against the tables of V86
 * ({@code ddl-auto: validate}: the context would not start otherwise). Only against the local MySQL.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.capture-priority.purge-enabled=false",
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false"
})
class FiscalDocumentMetamodelIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyEntityOfTheFiscalDocumentPackageIsInTheMetamodel() {
        String url = jdbc.execute((Connection c) -> c.getMetaData().getURL());
        assertThat(url).matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):.*");

        // abstract entities (FiscalDocument, GoodsInvoice) count too
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> declared = scanner.findCandidateComponents(FiscalDocument.class.getPackageName()).stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());
        Set<String> managed = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .map(Class::getName)
                .collect(Collectors.toSet());

        assertThat(declared).contains(FiscalDocument.class.getName(), GoodsInvoice.class.getName(), Nfe.class.getName(),
                Nfce.class.getName(), Nfse.class.getName(), Cte.class.getName(), FiscalDocumentItem.class.getName(),
                FiscalDocumentEvent.class.getName(), FiscalDocumentSource.class.getName(), CapturePeriodCount.class.getName());
        assertThat(managed).containsAll(declared);
    }
}
