package br.com.cshub.taxify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

@SpringBootApplication
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@EnableFeignClients(basePackages = "br.com.cshub.taxify.integration.sieg")
@EnableScheduling
public class TaxifyApplication {

    private static final Logger log = LoggerFactory.getLogger(TaxifyApplication.class);


    public static void main(String[] args) throws UnknownHostException {
        SpringApplication app = new SpringApplication(TaxifyApplication.class);
        SimpleCommandLinePropertySource source = new SimpleCommandLinePropertySource(args);
		addDefaultProfile(app, source);;
        Environment env = app.run(args).getEnvironment();
        log.info("""
                        Access URLs:
                        ----------------------------------------------------------
                        Local: http://127.0.0.1:{}{}
                        External: http://{}:{}{}
                        ----------------------------------------------------------
                        """,
                env.getProperty("server.port"), env.getProperty("server.servlet.context-path"),
                InetAddress.getLocalHost().getHostAddress(),
                env.getProperty("server.port"), env.getProperty("server.servlet.context-path"));
    }

    private static void addDefaultProfile(SpringApplication app, SimpleCommandLinePropertySource source) {
		if (!source.containsProperty("spring.profiles.active")
				&& !System.getenv().containsKey("SPRING_PROFILES_ACTIVE")) {
			app.setAdditionalProfiles("default");
		}
	}

}
