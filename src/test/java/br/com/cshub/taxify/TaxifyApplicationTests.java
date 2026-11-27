package br.com.cshub.taxify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false"})
class TaxifyApplicationTests {

	@Test
	void contextLoads() {
	}

}
