package br.com.cshub.taxify.dto.firmaccess;

import java.time.LocalDate;
import java.util.List;

/**
 * A situação do escritório do usuário logado, para as faixas da tela.
 *
 * @param status              PENDING_VERIFICATION, TRIAL, ACTIVE, PAST_DUE, BLOCKED, SUSPENDED ou CANCELED (efetiva hoje)
 * @param readOnly            true = só leitura (faixa de bloqueio, botões de escrita desabilitados)
 * @param reason              TRIAL_EXPIRED, ACCESS_EXPIRED, NON_PAYMENT ou MANUAL, quando bloqueado
 * @param trialDaysLeft       no teste: "faltam X dias"; 1 = "Seu teste termina hoje"
 * @param capturesStoppedDays no bloqueio: "as capturas automáticas estão paradas há N dias"
 * @param sefazRetentionDays  aviso da SEFAZ (cerca de 90 dias)
 * @param limits              uso × limite do teste; nulo fora do teste (sem limite)
 * @param platformAdmin       o usuário é admin da plataforma (mostra o menu "Plataforma")
 * @param recoveryPending     depois da reativação: os meses bloqueados ainda estão sendo recuperados (SIEG, e-Fisco)
 * @param recoveryMonths      esses meses, "AAAA-MM", em ordem; vazio quando nada falta
 * @param capturePanelPlatformEnabled o painel da captura da plataforma está ligado e o usuário é admin da plataforma
 *                            (mostra o item "Captura de XML" no console; TAXIFY_CAPTURE_PANEL_PLATFORM_ENABLED)
 */
public record FirmAccessDTO(String status, boolean exempt, boolean readOnly, String reason, LocalDate blockedSince,
                             LocalDate trialEndsOn, LocalDate accessUntil, Integer trialDaysLeft,
                             Integer capturesStoppedDays, int sefazRetentionDays, FirmAccessLimitsDTO limits, boolean platformAdmin,
                             boolean recoveryPending, List<String> recoveryMonths, boolean capturePanelPlatformEnabled) {
}
