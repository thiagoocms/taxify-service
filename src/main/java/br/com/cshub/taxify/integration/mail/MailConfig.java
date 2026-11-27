package br.com.cshub.taxify.integration.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.time.Duration;
import java.util.Locale;

/** Escolhe o {@link MailSender} por TAXIFY_MAIL_PROVIDER (SES ou LOG, padrão LOG) - F1-RN-17. */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public MailSender mailSender(MailProperties properties) {
        String provider = properties.getProvider();
        String from = properties.getFrom();
        String region = properties.getSesRegion();
        boolean logBody = properties.isLogBody();
        if ("SES".equals(provider == null ? "" : provider.strip().toUpperCase(Locale.ROOT))) {
            SesV2Client client = SesV2Client.builder()
                    .region(Region.of(region))
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))
                            .apiCallTimeout(Duration.ofSeconds(30))
                            .build())
                    .build();
            log.info("Mail: Amazon SES ({}), from {}", region, from);
            return guarded(new SesMailSender(client, from), properties);
        }
        log.info("Mail: LOG only - no e-mail is sent (TAXIFY_MAIL_PROVIDER=SES to send), from {}", from);
        return guarded(new LogMailSender(from, logBody), properties);
    }

    /** Allowlist and pace in front of the provider, for every e-mail (AV-RN-09, AV-RN-13). */
    static MailSender guarded(MailSender provider, MailProperties properties) {
        boolean filtered = properties.getRecipientAllowlist().stream().anyMatch(entry -> entry != null && !entry.isBlank());
        if (filtered) {
            log.info("Mail: recipient allowlist on - {} entries; other recipients are dropped", properties.getRecipientAllowlist().size());
        }
        return new GuardedMailSender(provider, properties.getRecipientAllowlist(), properties.getSendIntervalMs());
    }
}
