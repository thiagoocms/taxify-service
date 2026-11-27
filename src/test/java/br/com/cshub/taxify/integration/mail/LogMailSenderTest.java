package br.com.cshub.taxify.integration.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CT1-40 / F1-RN-17: a implementação de log nunca escreve o e-mail do destinatário e só mostra o corpo (com o token) com TAXIFY_MAIL_LOG_BODY=true. */
class LogMailSenderTest {

    private static final String RECIPIENT = "dona.ficticia@example.com";
    private static final MailMessage MESSAGE = new MailMessage(List.of(RECIPIENT), "Confirme seu e-mail",
            "abra https://taxify.exemplo.test/auth/verificar?token=TOKEN-SECRETO-123");

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LogMailSender.class);

    @BeforeEach
    void capture() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logs);
    }

    @Test
    void productionDefaultNeverLogsTheTokenNorTheAddress() {
        new LogMailSender("nao-responda@cshub.com.br", false).send(MESSAGE);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("from=nao-responda@cshub.com.br").contains("recipients=1")
                .doesNotContain(RECIPIENT).doesNotContain("TOKEN-SECRETO-123");
    }

    @Test
    void theBodyOnlyAppearsWhenTheDevelopmentFlagIsOn() {
        new LogMailSender("nao-responda@cshub.com.br", true).send(MESSAGE);

        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(1).getFormattedMessage()).contains("TOKEN-SECRETO-123");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(RECIPIENT));
    }
}
