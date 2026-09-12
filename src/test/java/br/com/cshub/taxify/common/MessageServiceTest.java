package br.com.cshub.taxify.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageServiceTest {

    // Loads the real i18n bundles, so this also guards against any getMessage("...")
    // call in the codebase referencing a key missing from the properties files.
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        var messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames("classpath:/i18n/fields", "classpath:/i18n/errors", "classpath:/i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageService = new MessageService(messageSource);
    }

    @Test
    void resolvesAnExistingKeyInTheDefaultLocale() {
        Locale.setDefault(new Locale("pt", "BR"));
        assertThat(messageService.getMessage("error.entity.not.found")).isEqualTo("Registro não encontrado.");
    }

    @Test
    void singleArgOverloadDelegatesToVarargsOverload() {
        assertThat(messageService.getMessage("error.plan.not.found"))
                .isEqualTo(messageService.getMessage("error.plan.not.found", ""));
    }

    @Test
    void interpolatesArgumentsIntoTheMessagePattern() {
        String message = messageService.getMessage("error.mandatory.fields", "name, login");
        assertThat(message).contains("name, login");
    }

    @Test
    void throwsForAKeyThatDoesNotExistInAnyBundle() {
        assertThatThrownBy(() -> messageService.getMessage("error.this.key.does.not.exist"))
                .isInstanceOf(NoSuchMessageException.class);
    }
}
