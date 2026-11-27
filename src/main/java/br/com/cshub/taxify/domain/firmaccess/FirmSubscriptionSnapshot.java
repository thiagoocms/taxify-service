package br.com.cshub.taxify.domain.firmaccess;

import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;

import java.time.LocalDate;

/** Os campos da assinatura que a trilha grava antes e depois de cada mudança (F1-RN-14, F1-RN-18). */
public record FirmSubscriptionSnapshot(FirmSubscriptionStatus status, LocalDate trialEndsOn, LocalDate accessUntil,
                                       LocalDate blockedSince, BlockedReason blockedReason, boolean migrated,
                                       boolean reviewed) {
}
