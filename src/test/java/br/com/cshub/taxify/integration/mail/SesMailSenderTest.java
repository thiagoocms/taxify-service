package br.com.cshub.taxify.integration.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F1-RN-17: uma falha do SES nunca leva o endereço do destinatário para o log nem para a exceção repassada. */
class SesMailSenderTest {

    private static final String RECIPIENT = "destinatario@exemplo.test";

    private final SesV2Client client = mock(SesV2Client.class);
    private final SesMailSender sender = new SesMailSender(client, "nao-responda@cshub.com.br");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SesMailSender.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void aRejectedMessageLogsOnlyTheErrorCodeStatusAndRequestId() {
        MessageRejectedException rejected = (MessageRejectedException) MessageRejectedException.builder()
                .message("Email address is not verified. The following identities failed the check: " + RECIPIENT)
                .statusCode(400)
                .requestId("req-123")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected")
                        .errorMessage("identities failed: " + RECIPIENT).build())
                .build();
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(rejected);

        assertThatThrownBy(() -> sender.send(new MailMessage(List.of(RECIPIENT), "Assunto", "corpo com token=segredo")))
                .isInstanceOfSatisfying(MailSendException.class, e -> {
                    assertThat(e.getCause()).isNull();
                    assertThat(e.getMessage()).doesNotContain(RECIPIENT).contains("MessageRejected").contains("400")
                            .contains("req-123");
                });

        assertThat(logs.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(RECIPIENT).doesNotContain("segredo");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("code=MessageRejected").contains("requestId=req-123");
    }

    @Test
    void aClientErrorLogsOnlyTheExceptionClass() {
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SdkClientException.create("Unable to reach SES for " + RECIPIENT));

        assertThatThrownBy(() -> sender.send(new MailMessage(List.of(RECIPIENT), "Assunto", "corpo")))
                .isInstanceOfSatisfying(MailSendException.class, e -> assertThat(e.getMessage()).doesNotContain(RECIPIENT));
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(RECIPIENT)
                .contains("error=SdkClientException"));
    }
}
