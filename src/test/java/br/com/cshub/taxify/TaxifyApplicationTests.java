package br.com.cshub.taxify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        // no scheduler talks to the outside world (SIEG, SEFAZ, e-Fisco, SES) while the context is up
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false", "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false"})
class TaxifyApplicationTests {

	@Test
	void contextLoads() {
	}

}
