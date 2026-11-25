package br.com.cshub.taxify.service.certidao;

import br.com.cshub.taxify.enumerated.CertidaoSituacao;
import br.com.cshub.taxify.enumerated.CertidaoTipo;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a SEFAZ-PE certidão (CRF or CND, as e-Fisco issues it) from its PDF: number, issue date, validity, situation
 * and the CNPJ it was issued for. The text is matched without accents and case, so small layout changes (a "Nº" or
 * "Número", "Válida até" or "Validade") still read; without a date of validity it is the issue date + 90 days
 * (e-Fisco's validity for the certidão).
 */
@Component
public class CertidaoPdfParser {

    /** e-Fisco's validity for a state certidão. */
    public static final int VALIDITY_DAYS = 90;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final String DATE = "(\\d{2}/\\d{2}/\\d{4})";
    private static final Pattern NUMBER = Pattern.compile(
            "(?:(?:certidao|controle)\\s*(?:n\\s*[o°º.]+|numero|nr\\.?)|(?:numero|n\\s*[o°º.]+) da certidao)\\s*[:.-]?\\s*([0-9][0-9./-]{4,}[0-9])");
    private static final Pattern ISSUED = Pattern.compile(
            "(?:data (?:de |da )?emissao|emitida em|emissao)\\s*[:.-]?\\s*(?:em\\s*)?" + DATE);
    private static final Pattern VALID_UNTIL = Pattern.compile(
            "(?:valida ate|validade ate|validade|valido ate|data de validade)\\s*[:.-]?\\s*(?:o dia\\s*|ate\\s*)?" + DATE);
    private static final Pattern VALID_DAYS = Pattern.compile("(?:validade|valida por|valido por)\\s*(?:de)?\\s*[:.-]?\\s*(\\d{2,3})\\s*\\(?[a-z ]*\\)?\\s*dias");
    /** A CNPJ written as such (12.345.678/0001-90, or 14 digits on their own) - after "CNPJ" when the text says so. */
    private static final String CNPJ_VALUE = "(?<![\\d./-])(\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}|\\d{14})(?![\\d/-])";
    private static final Pattern CNPJ_LABELED = Pattern.compile("cnpj[^0-9]{0,30}" + CNPJ_VALUE);
    private static final Pattern CNPJ = Pattern.compile(CNPJ_VALUE);

    /** What the PDF says. */
    public record Certidao(CertidaoTipo tipo, String numero, CertidaoSituacao situacao, LocalDate emitidaEm,
                           LocalDate validaAte, String cnpj) {
    }

    public static class InvalidCertidaoException extends RuntimeException {
        public InvalidCertidaoException(String message) {
            super(message);
        }
    }

    public Certidao parse(byte[] pdf) {
        return parseText(text(pdf));
    }

    /** The PDF's text, page after page. */
    static String text(byte[] pdf) {
        if (pdf == null || pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P') {
            throw new InvalidCertidaoException("o arquivo não é um PDF.");
        }
        try {
            PdfReader reader = new PdfReader(pdf);
            try {
                PdfTextExtractor extractor = new PdfTextExtractor(reader);
                StringBuilder text = new StringBuilder();
                for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                    text.append(extractor.getTextFromPage(page)).append('\n');
                }
                return text.toString();
            } finally {
                reader.close();
            }
        } catch (IOException | RuntimeException e) {
            throw new InvalidCertidaoException("o PDF não pôde ser lido (" + e.getMessage() + ").");
        }
    }

    /** The certidão from the PDF's text. */
    public Certidao parseText(String raw) {
        String text = normalize(raw);
        if (!text.contains("certidao")) {
            throw new InvalidCertidaoException("o PDF não parece uma certidão da SEFAZ.");
        }
        CertidaoSituacao situacao = situacao(text);
        if (situacao == null) {
            throw new InvalidCertidaoException("a situação (negativa, positiva ou positiva com efeito de negativa) não foi encontrada.");
        }
        LocalDate issued = date(ISSUED, text);
        LocalDate validUntil = date(VALID_UNTIL, text);
        if (validUntil == null && issued != null) {
            Matcher days = VALID_DAYS.matcher(text);
            validUntil = issued.plusDays(days.find() ? Integer.parseInt(days.group(1)) : VALIDITY_DAYS);
        }
        if (validUntil == null) {
            throw new InvalidCertidaoException("a data de validade não foi encontrada.");
        }
        Matcher number = NUMBER.matcher(text);
        return new Certidao(tipo(text), number.find() ? number.group(1) : null, situacao, issued, validUntil, cnpj(text));
    }

    /** The CNPJ (digits) after the "CNPJ" label, or the first one written as a CNPJ. */
    static String cnpj(String text) {
        Matcher labeled = CNPJ_LABELED.matcher(text);
        if (labeled.find()) {
            return labeled.group(1).replaceAll("\\D", "");
        }
        Matcher any = CNPJ.matcher(text);
        return any.find() ? any.group(1).replaceAll("\\D", "") : null;
    }

    /** "Certidão Negativa de Débitos" in the title = CND; "Regularidade Fiscal" (or anything else) = CRF. */
    static CertidaoTipo tipo(String text) {
        if (text.contains("regularidade fiscal")) {
            return CertidaoTipo.CRF;
        }
        return text.contains("negativa de debito") ? CertidaoTipo.CND : CertidaoTipo.CRF;
    }

    /**
     * The situation as e-Fisco writes it - "positiva com efeito(s) de negativa" first (it also contains both words),
     * then "certidão positiva" / "certidão negativa", then the statement ("não constam" / "constam" débitos).
     */
    static CertidaoSituacao situacao(String text) {
        if (Pattern.compile("positiva,? com efeitos? de negativa").matcher(text).find()) {
            return CertidaoSituacao.POSITIVA_EFEITO_NEGATIVA;
        }
        if (Pattern.compile("certidao (?:de regularidade fiscal )?positiva|situacao\\s*:?\\s*positiva").matcher(text).find()) {
            return CertidaoSituacao.POSITIVA;
        }
        if (Pattern.compile("certidao (?:de regularidade fiscal )?negativa|situacao\\s*:?\\s*negativa").matcher(text).find()) {
            return CertidaoSituacao.NEGATIVA;
        }
        if (Pattern.compile("nao (?:constam|existem|ha|consta)\\s+(?:\\S+\\s+){0,4}debitos?").matcher(text).find()) {
            return CertidaoSituacao.NEGATIVA;
        }
        if (Pattern.compile("(?:constam|existem|consta)\\s+(?:\\S+\\s+){0,4}debitos?").matcher(text).find()) {
            return CertidaoSituacao.POSITIVA;
        }
        return null;
    }

    private static LocalDate date(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1), DAY);
            } catch (DateTimeParseException e) {
                // a malformed date: try the next occurrence
            }
        }
        return null;
    }

    /** Lower case, no accents, single spaces. */
    static String normalize(String raw) {
        String plain = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replace(' ', ' ').replaceAll("\\s+", " ");
    }
}
