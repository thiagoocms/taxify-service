package br.com.cshub.taxify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parâmetros do Taxify SaaS fase 1 (seção 11 da spec): cadastro público, termos, teste grátis, verificação de e-mail
 * e avisos. Cada um vem de uma variável de ambiente (application.yaml, {@code taxify.saas.*}).
 */
@Component
@ConfigurationProperties(prefix = "taxify.saas")
public class SaasProperties {

    /** TAXIFY_SIGNUP_ENABLED - liga o cadastro público (F1-RN-11). */
    private boolean signupEnabled = false;
    /** TAXIFY_TERMS_VERSION - versão vigente dos termos; vazia ou RASCUNHO* mantém o cadastro fechado. */
    private String termsVersion = "";
    private String termsUrl = "";
    private String privacyUrl = "";
    private String dpaUrl = "";
    /** TAXIFY_TRIAL_DAYS (F1-RN-05). */
    private int trialDays = 14;
    /** TAXIFY_TRIAL_MAX_COMPANIES (F1-RN-10a). */
    private int trialMaxCompanies = 5;
    /** TAXIFY_TRIAL_MAX_USERS, contando o ADMIN (F1-RN-10a). */
    private int trialMaxUsers = 2;
    /** TAXIFY_SIGNUP_MAX_PER_IP_PER_DAY (F1-RN-06). */
    private int signupMaxPerIpPerDay = 3;
    /** TAXIFY_EMAIL_VERIFICATION_HOURS (F1-RN-03). */
    private int emailVerificationHours = 24;
    /** TAXIFY_EMAIL_RESEND_PER_HOUR (F1-RN-03). */
    private int emailResendPerHour = 3;
    /** TAXIFY_EMAIL_RESEND_PER_DAY - reenvios por login por dia civil de Recife (decisão do Tony). */
    private int emailResendPerDay = 5;
    /** TAXIFY_EMAIL_RESEND_PER_IP_PER_DAY - reenvios por IP por dia civil de Recife (decisão do Tony). */
    private int emailResendPerIpPerDay = 10;
    /**
     * TAXIFY_UNBLOCK_RECOVERY_MONTHS - na reativação, quantos meses para trás (a partir do mês atual) os meses
     * bloqueados são recuperados; alinhado a FISCAL_XML_PURGE_RETENTION_MONTHS.
     */
    private int unblockRecoveryMonths = 3;
    /** TAXIFY_UNVERIFIED_PURGE_DAYS (F1-RN-03). */
    private int unverifiedPurgeDays = 7;
    /** TAXIFY_PUBLIC_URL - endereço do Taxify nos links dos e-mails (verificação, avisos). */
    private String publicUrl = "https://taxify.cshub.com.br";
    /**
     * TAXIFY_TRUSTED_PROXIES - de quem o backend aceita o X-Forwarded-For (F1-RN-06). Vazio = os endereços de
     * loopback e de rede privada (a rede interna do Docker, por onde chegam o Caddy e o nginx do front).
     */
    private List<String> trustedProxies = new ArrayList<>();
    /** TAXIFY_ACCESS_ENABLED - o registro diário do BLOCKED (FirmAccessScheduler). */
    private boolean accessEnabled = true;
    /** TAXIFY_UNVERIFIED_PURGE_ENABLED - o expurgo dos cadastros não verificados (UnverifiedSignupPurge). */
    private boolean purgeEnabled = true;
    /** TAXIFY_TRIAL_NOTICE_ENABLED - os avisos do fim do teste (TrialNoticeScheduler). */
    private boolean trialNoticeEnabled = true;

    public boolean isAccessEnabled() {
        return accessEnabled;
    }

    public void setAccessEnabled(boolean accessEnabled) {
        this.accessEnabled = accessEnabled;
    }

    public boolean isPurgeEnabled() {
        return purgeEnabled;
    }

    public void setPurgeEnabled(boolean purgeEnabled) {
        this.purgeEnabled = purgeEnabled;
    }

    public boolean isTrialNoticeEnabled() {
        return trialNoticeEnabled;
    }

    public void setTrialNoticeEnabled(boolean trialNoticeEnabled) {
        this.trialNoticeEnabled = trialNoticeEnabled;
    }

    public boolean isSignupEnabled() {
        return signupEnabled;
    }

    public void setSignupEnabled(boolean signupEnabled) {
        this.signupEnabled = signupEnabled;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public void setTermsVersion(String termsVersion) {
        this.termsVersion = termsVersion == null ? "" : termsVersion.strip();
    }

    public String getTermsUrl() {
        return termsUrl;
    }

    public void setTermsUrl(String termsUrl) {
        this.termsUrl = termsUrl;
    }

    public String getPrivacyUrl() {
        return privacyUrl;
    }

    public void setPrivacyUrl(String privacyUrl) {
        this.privacyUrl = privacyUrl;
    }

    public String getDpaUrl() {
        return dpaUrl;
    }

    public void setDpaUrl(String dpaUrl) {
        this.dpaUrl = dpaUrl;
    }

    public int getTrialDays() {
        return trialDays;
    }

    public void setTrialDays(int trialDays) {
        this.trialDays = trialDays;
    }

    public int getTrialMaxCompanies() {
        return trialMaxCompanies;
    }

    public void setTrialMaxCompanies(int trialMaxCompanies) {
        this.trialMaxCompanies = trialMaxCompanies;
    }

    public int getTrialMaxUsers() {
        return trialMaxUsers;
    }

    public void setTrialMaxUsers(int trialMaxUsers) {
        this.trialMaxUsers = trialMaxUsers;
    }

    public int getSignupMaxPerIpPerDay() {
        return signupMaxPerIpPerDay;
    }

    public void setSignupMaxPerIpPerDay(int signupMaxPerIpPerDay) {
        this.signupMaxPerIpPerDay = signupMaxPerIpPerDay;
    }

    public int getEmailVerificationHours() {
        return emailVerificationHours;
    }

    public void setEmailVerificationHours(int emailVerificationHours) {
        this.emailVerificationHours = emailVerificationHours;
    }

    public int getEmailResendPerHour() {
        return emailResendPerHour;
    }

    public void setEmailResendPerHour(int emailResendPerHour) {
        this.emailResendPerHour = emailResendPerHour;
    }

    public int getEmailResendPerDay() {
        return emailResendPerDay;
    }

    public void setEmailResendPerDay(int emailResendPerDay) {
        this.emailResendPerDay = emailResendPerDay;
    }

    public int getEmailResendPerIpPerDay() {
        return emailResendPerIpPerDay;
    }

    public void setEmailResendPerIpPerDay(int emailResendPerIpPerDay) {
        this.emailResendPerIpPerDay = emailResendPerIpPerDay;
    }

    public int getUnblockRecoveryMonths() {
        return unblockRecoveryMonths;
    }

    public void setUnblockRecoveryMonths(int unblockRecoveryMonths) {
        this.unblockRecoveryMonths = unblockRecoveryMonths;
    }

    public int getUnverifiedPurgeDays() {
        return unverifiedPurgeDays;
    }

    public void setUnverifiedPurgeDays(int unverifiedPurgeDays) {
        this.unverifiedPurgeDays = unverifiedPurgeDays;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
    }
}
