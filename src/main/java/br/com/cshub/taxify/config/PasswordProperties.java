package br.com.cshub.taxify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Senha e sessões (spec 2026-11-27, seção 5): "Esqueci a senha", regra de senha, troca pelo usuário e aviso de senha
 * alterada. Cada parâmetro vem de uma variável de ambiente ({@code taxify.password.*} no application.yaml).
 */
@Component
@ConfigurationProperties(prefix = "taxify.password")
public class PasswordProperties {

    /** TAXIFY_PASSWORD_RESET_ENABLED - liga o "Esqueci a senha" (SN-RN-14). Na EC2 só depois da saída do sandbox do SES. */
    private boolean resetEnabled = false;
    /** TAXIFY_PASSWORD_RESET_MINUTES - validade do link (SN-RN-03, P-SN-01). */
    private int resetMinutes = 60;
    /** TAXIFY_PASSWORD_RESET_PER_HOUR - links por conta nos últimos 60 minutos (SN-RN-04). */
    private int resetPerHour = 3;
    /** TAXIFY_PASSWORD_RESET_PER_DAY - links por conta no dia civil de Recife (SN-RN-04). */
    private int resetPerDay = 5;
    /** TAXIFY_PASSWORD_RESET_PER_IP_PER_DAY - links por IP no dia civil de Recife (SN-RN-04). */
    private int resetPerIpPerDay = 10;
    /** Contas atendidas por pedido (SN-RN-02): as mais recentes. */
    private int resetMaxAccounts = 5;
    /** TAXIFY_PASSWORD_MIN_LENGTH - tamanho mínimo da senha nova, em caracteres (SN-RN-08, P-SN-04). */
    private int minLength = 8;
    /** TAXIFY_PASSWORD_CHANGE_MAX_FAILURES - erros de senha atual em 60 minutos antes do 429 (SN-RN-09). */
    private int changeMaxFailures = 5;
    /** TAXIFY_PASSWORD_CHANGED_NOTICE_ENABLED - e-mail de senha alterada (SN-RN-12, P-SN-08). */
    private boolean changedNoticeEnabled = true;
    /** TAXIFY_PASSWORD_RESET_PURGE_ENABLED - a limpeza diária dos links com mais de 48 h (SN-RN-15). */
    private boolean resetPurgeEnabled = true;
    /** TAXIFY_PASSWORD_ASYNC_MAX_CONCURRENT - pedidos de "Esqueci a senha" e avisos processados ao mesmo tempo. */
    private int asyncMaxConcurrent = 8;
    /** TAXIFY_PASSWORD_ASYNC_QUEUE_SIZE - na fila; acima disso o trabalho é descartado (a resposta continua 202). */
    private int asyncQueueSize = 100;

    public int getAsyncMaxConcurrent() {
        return asyncMaxConcurrent;
    }

    public void setAsyncMaxConcurrent(int asyncMaxConcurrent) {
        this.asyncMaxConcurrent = asyncMaxConcurrent;
    }

    public int getAsyncQueueSize() {
        return asyncQueueSize;
    }

    public void setAsyncQueueSize(int asyncQueueSize) {
        this.asyncQueueSize = asyncQueueSize;
    }

    public boolean isResetEnabled() {
        return resetEnabled;
    }

    public void setResetEnabled(boolean resetEnabled) {
        this.resetEnabled = resetEnabled;
    }

    public int getResetMinutes() {
        return resetMinutes;
    }

    public void setResetMinutes(int resetMinutes) {
        this.resetMinutes = resetMinutes;
    }

    public int getResetPerHour() {
        return resetPerHour;
    }

    public void setResetPerHour(int resetPerHour) {
        this.resetPerHour = resetPerHour;
    }

    public int getResetPerDay() {
        return resetPerDay;
    }

    public void setResetPerDay(int resetPerDay) {
        this.resetPerDay = resetPerDay;
    }

    public int getResetPerIpPerDay() {
        return resetPerIpPerDay;
    }

    public void setResetPerIpPerDay(int resetPerIpPerDay) {
        this.resetPerIpPerDay = resetPerIpPerDay;
    }

    public int getResetMaxAccounts() {
        return resetMaxAccounts;
    }

    public void setResetMaxAccounts(int resetMaxAccounts) {
        this.resetMaxAccounts = resetMaxAccounts;
    }

    public int getMinLength() {
        return minLength;
    }

    public void setMinLength(int minLength) {
        this.minLength = minLength;
    }

    public int getChangeMaxFailures() {
        return changeMaxFailures;
    }

    public void setChangeMaxFailures(int changeMaxFailures) {
        this.changeMaxFailures = changeMaxFailures;
    }

    public boolean isChangedNoticeEnabled() {
        return changedNoticeEnabled;
    }

    public void setChangedNoticeEnabled(boolean changedNoticeEnabled) {
        this.changedNoticeEnabled = changedNoticeEnabled;
    }

    public boolean isResetPurgeEnabled() {
        return resetPurgeEnabled;
    }

    public void setResetPurgeEnabled(boolean resetPurgeEnabled) {
        this.resetPurgeEnabled = resetPurgeEnabled;
    }
}
