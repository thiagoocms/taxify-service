package br.com.cshub.taxify.service.pendency.alert;

import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.integration.mail.MailMessage;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * O e-mail do resumo diário de pendências ALTA (AV-RN-06). Texto simples. Leva só o nome da empresa (até 60
 * caracteres), o rótulo do tipo e a quantidade; <b>nunca</b> CNPJ, CPF, IE, chave de acesso, número ou série de nota,
 * emitente, destinatário, valores, o {@code title} e o {@code detail} da pendência nem o comentário de fechamento - por
 * isso a entrada é só {@link PendencyAlertCandidate}. Sem nome de empresa no assunto. Pura: sem I/O.
 */
public final class PendencyAlertMailTemplate {

    static final int COMPANY_NAME_MAX = 60;
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private PendencyAlertMailTemplate() {
    }

    /** O que entra no e-mail de um usuário. */
    public record Digest(String to, String userName, String firmName, List<PendencyAlertCandidate> items, long totalOpenHigh,
                         String publicUrl, int maxCompaniesListed) {
    }

    public static MailMessage build(Digest digest) {
        List<CompanyLine> companies = companies(digest.items());
        int listed = Math.min(companies.size(), Math.max(digest.maxCompaniesListed(), 0));
        String url = stripSlash(digest.publicUrl());

        StringBuilder body = new StringBuilder();
        body.append("Olá, ").append(firstName(digest.userName())).append(".\n\n");
        body.append("Estas são as pendências altas novas nas empresas da sua carteira no escritório ")
                .append(digest.firmName() == null ? "" : digest.firmName().strip()).append(":\n\n");
        for (CompanyLine line : companies.subList(0, listed)) {
            body.append("- ").append(line.text()).append('\n');
        }
        if (companies.size() > listed) {
            body.append("e mais ").append(companies.size() - listed).append(" empresa(s)\n");
        }
        body.append('\n').append(totalLine(digest.totalOpenHigh())).append("\n\n");
        body.append("Veja no Taxify: ").append(url).append("/pendencias?severidade=ALTA&status=ABERTA\n\n");
        body.append("Você recebe este resumo porque tem acesso às pendências destas empresas no Taxify. Para desligar, ")
                .append("entre em Minha conta > Avisos por e-mail: ").append(url).append("/minha-conta\n\n");
        body.append("Taxify - CS Hub\n");

        String subject = "Taxify: " + digest.items().size() + " pendência(s) alta(s) nova(s) na sua carteira";
        return new MailMessage(List.of(digest.to()), subject, body.toString());
    }

    /** Quantas empresas o resumo cobre. */
    public static int companiesCount(List<PendencyAlertCandidate> items) {
        return (int) items.stream().map(PendencyAlertCandidate::companyId).distinct().count();
    }

    private record CompanyLine(UUID companyId, String name, String text) {
    }

    /** Uma linha por empresa, em ordem alfabética; os tipos pela quantidade (maior primeiro) e depois pelo rótulo. */
    static List<CompanyLine> companies(List<PendencyAlertCandidate> items) {
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, Map<PendencyType, Integer>> counts = new LinkedHashMap<>();
        for (PendencyAlertCandidate item : items) {
            names.putIfAbsent(item.companyId(), companyName(item.companyName()));
            counts.computeIfAbsent(item.companyId(), id -> new EnumMap<>(PendencyType.class)).merge(item.type(), 1, Integer::sum);
        }
        Collator collator = Collator.getInstance(PT_BR);
        collator.setStrength(Collator.PRIMARY);
        List<CompanyLine> lines = new ArrayList<>();
        for (Map.Entry<UUID, Map<PendencyType, Integer>> entry : counts.entrySet()) {
            String name = names.get(entry.getKey());
            List<String> parts = entry.getValue().entrySet().stream()
                    .sorted(Comparator.<Map.Entry<PendencyType, Integer>>comparingInt(Map.Entry::getValue).reversed()
                            .thenComparing(e -> e.getKey().label, collator))
                    .map(e -> e.getKey().label + " (" + e.getValue() + ")")
                    .toList();
            lines.add(new CompanyLine(entry.getKey(), name, name + ": " + String.join("; ", parts)));
        }
        lines.sort(Comparator.comparing(CompanyLine::name, collator).thenComparing(line -> line.companyId().toString()));
        return lines;
    }

    /** Um bloco de dígitos com pontuação de documento (ponto, barra, hífen, espaço), em qualquer posição do nome. */
    private static final java.util.regex.Pattern NUMBER_BLOCK = java.util.regex.Pattern.compile("\\d[\\d./\\-\\s]*\\d|\\d");
    /** Só é documento quando tem pelo menos 8 dígitos (a raiz do CNPJ) - "3M do Brasil" fica como está. */
    private static final int DOCUMENT_MIN_DIGITS = 8;
    static final String NO_NAME = "Empresa sem nome";

    /**
     * AV-RN-06: o nome da empresa, sem número de documento. O MEI costuma vir como "12.345.678 FULANO DE TAL" (a raiz
     * do CNPJ), com o CPF no fim ou com o número no meio; todo bloco numérico com 8 dígitos ou mais sai do e-mail.
     * Depois, no máximo 60 caracteres.
     */
    static String companyName(String name) {
        String value = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        value = withoutDocuments(value);
        if (value.isEmpty()) {
            return NO_NAME;
        }
        if (value.codePointCount(0, value.length()) <= COMPANY_NAME_MAX) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, COMPANY_NAME_MAX)).strip();
    }

    /** Tira todo bloco numérico com pelo menos 8 dígitos; depois junta os espaços e os hífens que sobraram soltos. */
    private static String withoutDocuments(String value) {
        java.util.regex.Matcher matcher = NUMBER_BLOCK.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            long digits = matcher.group().chars().filter(Character::isDigit).count();
            matcher.appendReplacement(result, digits >= DOCUMENT_MIN_DIGITS ? " " : java.util.regex.Matcher.quoteReplacement(matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString()
                .replaceAll("(\\s*-\\s*){2,}", " - ")
                .replaceAll("\\s+", " ")
                .strip()
                .replaceAll("^[\\s\\-]+|[\\s\\-]+$", "");
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.strip().split("\\s+")[0];
    }

    static String totalLine(long total) {
        return total == 1
                ? "Ao todo, há 1 pendência alta aberta na sua carteira hoje."
                : "Ao todo, há " + total + " pendências altas abertas na sua carteira hoje.";
    }

    private static String stripSlash(String url) {
        String value = url == null ? "" : url.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
