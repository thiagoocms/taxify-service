package br.com.cshub.taxify.tools;

import jakarta.persistence.Entity;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the MySQL DDL of every entity, with no database connection - what the table
 * migrations (db.migration) were generated from, and a quick way to see what a new one must
 * declare. Manual:
 * {@code ./mvnw test -Dtest=SchemaDdlGenerator -Dddl.out=target/schema.sql}
 */
@EnabledIfSystemProperty(named = "ddl.out", matches = ".+")
class SchemaDdlGenerator {

    @Test
    void generate() throws Exception {
        Path out = Path.of(System.getProperty("ddl.out", "target/schema.sql"));
        Files.deleteIfExists(out);
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("jakarta.persistence.database-product-name", "MySQL")
                .applySetting("jakarta.persistence.database-major-version", "8")
                .applySetting("jakarta.persistence.database-minor-version", "0")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName())
                .applySetting("hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName())
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", out.toAbsolutePath().toString())
                .applySetting("hibernate.hbm2ddl.delimiter", ";")
                .applySetting("hibernate.format_sql", "true")
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .build();
        MetadataSources sources = new MetadataSources(registry);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.findCandidateComponents("br.com.cshub.taxify.domain").stream()
                .map(bean -> bean.getBeanClassName())
                .sorted()
                .forEach(sources::addAnnotatedClassName);
        MetadataImplementor metadata = (MetadataImplementor) sources.buildMetadata();
        metadata.buildSessionFactory().close();
    }
}
