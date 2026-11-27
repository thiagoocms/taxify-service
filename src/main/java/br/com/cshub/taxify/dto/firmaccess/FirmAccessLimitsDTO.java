package br.com.cshub.taxify.dto.firmaccess;

/** Limite do teste (F1-RN-10a): {@code companiesInUse >= maxCompanies} desabilita "Nova empresa"; idem usuários. */
public record FirmAccessLimitsDTO(String planName, int maxCompanies, long companiesInUse, int maxUsers, long usersInUse) {
}
