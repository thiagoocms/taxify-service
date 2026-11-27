package br.com.cshub.taxify.service.password;

import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.PasswordChangeChannel;
import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSendException;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.PasswordResetTokenRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SN-RN-12 (CT-SN-31), SN-RN-15 (CT-SN-33) and the reset link in the fragment (SN-RN-03). */
class PasswordNoticeAndPurgeTest {

    private final UserRepository users = mock(UserRepository.class);
    private final MailSender mail = mock(MailSender.class);
    private final PasswordProperties properties = new PasswordProperties();
    private final PasswordChangeNotifier notifier = new PasswordChangeNotifier(users, mail, properties, Runnable::run);
    private final LocalDateTime when = LocalDateTime.of(2026, 11, 27, 10, 30);

    private User bruno() {
        User user = new User(UUID.randomUUID());
        user.setLogin("bruno.ficticio");
        user.setEmail("bruno@exemplo.test");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    /** CT-SN-31: one e-mail per path, with the text of the path, no password and no action link. */
    @Test
    void theNoticeSaysWhenAndHow() {
        User bruno = bruno();
        String[] expected = {"por você pela redefinição por e-mail", "por você em Minha conta", "pelo administrador do escritório"};
        PasswordChangeChannel[] channels = {PasswordChangeChannel.RESET, PasswordChangeChannel.SELF, PasswordChangeChannel.ADMIN};
        for (int i = 0; i < channels.length; i++) {
            notifier.onPasswordChanged(new PasswordChangedEvent(bruno.getId(), channels[i], when));
        }
        ArgumentCaptor<MailMessage> sent = ArgumentCaptor.forClass(MailMessage.class);
        verify(mail, org.mockito.Mockito.times(3)).send(sent.capture());
        for (int i = 0; i < channels.length; i++) {
            MailMessage message = sent.getAllValues().get(i);
            assertThat(message.to()).containsExactly("bruno@exemplo.test");
            assertThat(message.body()).contains("bruno.ficticio").contains("27/11/2026 10:30").contains("horário de Recife")
                    .contains(expected[i]).contains("Se não foi você, fale com o administrador do seu escritório.")
                    .doesNotContain("http");
        }
    }

    /** CT-SN-31: a SES failure only logs - the change stands (no exception to the caller). */
    @Test
    void aMailFailureOnlyLogs() {
        User bruno = bruno();
        doThrow(new MailSendException("Falha no envio pelo SES (error=X)")).when(mail).send(any());
        assertThatCode(() -> notifier.onPasswordChanged(new PasswordChangedEvent(bruno.getId(), PasswordChangeChannel.ADMIN, when)))
                .doesNotThrowAnyException();
        doThrow(new IllegalStateException("x")).when(mail).send(any());
        assertThatCode(() -> notifier.onPasswordChanged(new PasswordChangedEvent(bruno.getId(), PasswordChangeChannel.SELF, when)))
                .doesNotThrowAnyException();
    }

    @Test
    void theNoticeCanBeSwitchedOff() {
        User bruno = bruno();
        properties.setChangedNoticeEnabled(false);
        notifier.onPasswordChanged(new PasswordChangedEvent(bruno.getId(), PasswordChangeChannel.SELF, when));
        verify(mail, never()).send(any());
    }

    /** SN-RN-03: the token goes in the fragment - the browser never sends it to the server. */
    @Test
    void theResetLinkCarriesTheTokenInTheFragment() {
        assertThat(PasswordMailTemplates.resetLink("https://taxify.exemplo.test/", "abc_DEF-123"))
                .isEqualTo("https://taxify.exemplo.test/auth/redefinir-senha#token=abc_DEF-123");
    }

    /** CT-SN-33: on 27/11 at 03:30 the purge removes what was requested before 25/11 03:30 (48 h). */
    @Test
    void thePurgeRemovesLinksOlderThan48Hours() {
        PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
        MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 27, 3, 30));
        PasswordResetPurgeScheduler purge = new PasswordResetPurgeScheduler(tokens, properties, clock);
        when(tokens.deleteRequestedBefore(LocalDateTime.of(2026, 11, 25, 3, 30))).thenReturn(1);

        purge.tick();
        verify(tokens).deleteRequestedBefore(LocalDateTime.of(2026, 11, 25, 3, 30));

        properties.setResetPurgeEnabled(false);
        purge.tick();
        verify(tokens, org.mockito.Mockito.times(1)).deleteRequestedBefore(any());
    }

    /** Review item 3: the notice goes to the executor - the request thread does not wait for the SES; full = dropped. */
    @Test
    void theNoticeRunsOffTheRequestThread() {
        User bruno = bruno();
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        PasswordChangeNotifier deferred = new PasswordChangeNotifier(users, mail, properties, queued::add);
        deferred.onPasswordChanged(new PasswordChangedEvent(bruno.getId(), PasswordChangeChannel.SELF, when));
        verify(mail, never()).send(any());
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(mail).send(any());

        PasswordChangeNotifier full = new PasswordChangeNotifier(users, mail, properties, command -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        });
        assertThatCode(() -> full.onPasswordChanged(new PasswordChangedEvent(bruno.getId(), PasswordChangeChannel.ADMIN, when)))
                .doesNotThrowAnyException();
    }
}
