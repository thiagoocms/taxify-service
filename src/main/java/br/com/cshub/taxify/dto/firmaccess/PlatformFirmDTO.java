package br.com.cshub.taxify.dto.firmaccess;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Uma linha do console. Nunca traz nome, CNPJ ou dado fiscal das empresas clientes, nem colaboradores que não sejam
 * ADMIN (F1-RN-15).
 */
public record PlatformFirmDTO(String id, String name, String documentNumber, LocalDateTime createdDate, String status,
                      boolean exempt, boolean readOnly, String reason, LocalDate trialEndsOn, LocalDate accessUntil,
                      LocalDate blockedSince, boolean migrated, boolean reviewed, LocalDateTime reviewedAt,
                      String reviewedBy, long companiesInUse, long companiesInactive, long usersInUse,
                      String contactPhone, String billingEmail, List<FirmAdminContactDTO> admins) {
}
