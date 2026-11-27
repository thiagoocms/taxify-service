package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A situação de acesso efetiva de um escritório num dia (F1-RN-07), calculada por {@link FirmAccessRules}.
 *
 * @param status               situação efetiva (um TRIAL vencido já sai como BLOCKED, sem esperar o agendador)
 * @param exempt               o escritório da CS Hub ({@code PLATFORM_FIRM_ID}, F1-RN-12): nunca bloqueado nem limitado
 * @param readOnly             BLOCKED, SUSPENDED, CANCELED (e o PENDING_VERIFICATION, que nem entra): só leitura (F1-RN-08)
 * @param reason               motivo do bloqueio, quando {@code readOnly}
 * @param blockedSince         primeiro dia do bloqueio, quando conhecido
 * @param trialDaysLeft        no TRIAL: {@code trial_ends_on − hoje + 1} (1 = "termina hoje")
 * @param capturesStoppedDays  no bloqueio: dias desde {@code blockedSince} (F1-RN-09)
 * @param missingSubscription  escritório sem linha em firm_subscriptions (tratado como SUSPENDED, F1-RN-07)
 */
public record FirmAccess(UUID firmId,
                         FirmSubscriptionStatus status,
                         boolean exempt,
                         boolean readOnly,
                         BlockedReason reason,
                         LocalDate blockedSince,
                         LocalDate trialEndsOn,
                         LocalDate accessUntil,
                         Integer trialDaysLeft,
                         Integer capturesStoppedDays,
                         boolean missingSubscription) {

    /** Em teste e sem isenção: vale o limite de empresas e usuários (F1-RN-10a). */
    public boolean trialLimited() {
        return !exempt && status == FirmSubscriptionStatus.TRIAL;
    }

    /** Capturas e rotinas automáticas que gravam dados (F1-RN-10). */
    public boolean allowsAutomaticRoutines() {
        return exempt || status == FirmSubscriptionStatus.TRIAL || status == FirmSubscriptionStatus.ACTIVE
                || status == FirmSubscriptionStatus.PAST_DUE;
    }

    /** Antes da verificação do e-mail o login é recusado (F1-RN-03). */
    public boolean pendingVerification() {
        return status == FirmSubscriptionStatus.PENDING_VERIFICATION;
    }
}
