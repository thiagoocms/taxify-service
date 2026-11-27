package br.com.cshub.taxify.dto.firmaccess;

import java.time.LocalDate;

/**
 * Corpo das ações {@code POST /platform/firms/{id}/access/{acao}}.
 *
 * @param trialEndsOn extend-trial (obrigatória, >= hoje) e convert-to-trial (opcional; padrão hoje + TAXIFY_TRIAL_DAYS − 1)
 * @param accessUntil release (opcional; nulo = sem fim; >= hoje)
 * @param reason      motivo (obrigatório no suspend; opcional nas demais), gravado no evento
 */
public record FirmAccessActionDTO(LocalDate trialEndsOn, LocalDate accessUntil, String reason) {
}
