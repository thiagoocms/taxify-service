package br.com.cshub.taxify.integration.sefaz;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An A1 (.pfx / PKCS#12) opened in memory - no key ever written to disk. Gives the TLS
 * context for the SEFAZ mutual-TLS call and what's shown on screen (holder, CNPJ, validity).
 */
public final class A1Certificate {

    /** ICP-Brasil e-CNPJ subject CN: "RAZAO SOCIAL:12345678000199". */
    private static final Pattern CNPJ_IN_CN = Pattern.compile(":(\\d{14})\\b");

    private final KeyStore keyStore;
    private final char[] password;
    private final X509Certificate certificate;

    private A1Certificate(KeyStore keyStore, char[] password, X509Certificate certificate) {
        this.keyStore = keyStore;
        this.password = password;
        this.certificate = certificate;
    }

    /** @throws GeneralSecurityException wrong password, not a PKCS#12 or no private key inside */
    public static A1Certificate open(byte[] pfx, String password) throws GeneralSecurityException {
        char[] chars = password == null ? new char[0] : password.toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try {
            keyStore.load(new ByteArrayInputStream(pfx), chars);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("Could not open the certificate (wrong password or not a .pfx)", e);
        }
        for (String alias : Collections.list(keyStore.aliases())) {
            if (keyStore.isKeyEntry(alias) && keyStore.getCertificate(alias) instanceof X509Certificate x509) {
                return new A1Certificate(keyStore, chars, x509);
            }
        }
        throw new GeneralSecurityException("The .pfx has no private key");
    }

    public SSLContext sslContext() throws GeneralSecurityException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null); // server trust: the JVM's default (SEFAZ AN chains to a public root)
        return context;
    }

    public String subjectName() {
        return certificate.getSubjectX500Principal().getName();
    }

    /** The holder's CNPJ, from the CN - null for certificates that don't follow the e-CNPJ layout. */
    public String cnpj() {
        Matcher matcher = CNPJ_IN_CN.matcher(subjectName());
        return matcher.find() ? matcher.group(1) : null;
    }

    public LocalDateTime validFrom() {
        return LocalDateTime.ofInstant(certificate.getNotBefore().toInstant(), ZoneId.systemDefault());
    }

    public LocalDateTime validUntil() {
        return LocalDateTime.ofInstant(certificate.getNotAfter().toInstant(), ZoneId.systemDefault());
    }
}
