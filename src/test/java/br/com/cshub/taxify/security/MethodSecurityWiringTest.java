package br.com.cshub.taxify.security;

import br.com.cshub.taxify.rest.CompanyResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The annotations only protect anything if the real controller beans are proxied by the
 * permission interceptor - this calls the actual CompanyResource bean and expects the
 * call to be refused before reaching the service.
 */
@SpringBootTest(properties = {"taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        // no scheduler talks to the outside world (SIEG, SEFAZ, e-Fisco, SES) while the context is up
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false", "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false"})
class MethodSecurityWiringTest {

    @Autowired
    private CompanyResource companyResource;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void employeeCannotCreateACompanyThroughTheRealBean() {
        TestingAuthenticationToken employee = new TestingAuthenticationToken("ana", null, "EMPLOYEE", "COMPANIES_VIEW");
        employee.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(employee);

        assertThatThrownBy(() -> companyResource.create(null)).isInstanceOf(AccessDeniedException.class);
    }
}
