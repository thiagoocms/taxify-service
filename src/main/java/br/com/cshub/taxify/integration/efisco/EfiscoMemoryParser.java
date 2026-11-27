package br.com.cshub.taxify.integration.efisco;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads e-Fisco's "Memória de Cálculo de Nota Fiscal" (PRConsultarNotaFiscalMemoria): the access key, the rule set
 * ("regras de Emitente com Regime Tributário Simples Nacional"), the "Tipos de Cálculo" table and, item by item
 * ("Cálculo Mercadoria: 258 - ..."), the variables e-Fisco writes as {@code Nome[valor] = expressão}. The text keeps
 * every line, for the screen to show it whole; the numbers are only what the comparison needs.
 */
public final class EfiscoMemoryParser {

    private static final Pattern ACCESS_KEY = Pattern.compile("\\b(\\d{44})\\b");
    private static final Pattern RULES = Pattern.compile("\\(Cálculo baseado nas regras de ([^)]+)\\)");
    /** "CREDENCIAMENTO (26) 2 2 + 25 = 27 S 26 S IND TEC CREDENCIADA S" */
    private static final Pattern TYPE = Pattern.compile(
            "^([A-ZÀ-Ú ]+?)\\s*\\(([^)]*)\\)\\s+(\\d+)\\s+(\\d+)\\s*\\+\\s*(\\d+)\\s*=\\s*(\\d+)\\s+([SN])\\s+(\\d+)\\s+([SN])\\s+(.*?)\\s+([SN])$");
    /**
     * "ValorPartidaAntecipado[2400.0000000000]" or, as the detail page writes it, "ICMSAntecipadoItem[ 1180.46 ]" - also
     * names with spaces ("CoefAgregacaoNorteNE da Mercadoria").
     */
    private static final Pattern VALUE = Pattern.compile("([A-Za-zÀ-ú][\\wÀ-ú ]*?)\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\]");
    private static final Pattern ITEM = Pattern.compile("^Cálculo Mercadoria:\\s*(.+)$");
    private static final Pattern ICMS_KIND = Pattern.compile("^ICMS Antecipado do Item\\s*\\((.+)\\)\\s*$");

    private EfiscoMemoryParser() {
    }

    /** A "Tipos de Cálculo" row: CNAE, CREDENCIAMENTO, MERCADORIA... - with its priority and whether it stops the others. */
    public record CalculationType(String tipo, String codigo, int prioridade, boolean interrompe, String descricao) {
    }

    /** One item ("Cálculo Mercadoria") as e-Fisco calculated it. Rates as fractions (0.205); percentual as written (5.5). */
    public record Item(String mercadoria, String calculo, BigDecimal valorItem, BigDecimal partida, BigDecimal coefAgregacao,
                       BigDecimal base, BigDecimal aliquotaInterna, BigDecimal aliquotaInterestadual,
                       BigDecimal percentualCargaLiquida, BigDecimal credito, BigDecimal icms) {
    }

    public record Memory(String accessKey, String rules, List<CalculationType> types, List<Item> items, String text) {

        public BigDecimal icmsTotal() {
            return items.stream().map(Item::icms).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** From the page HTML: tags out, one line per block, the numbers read. */
    public static Memory parseHtml(String html) {
        return parse(text(html));
    }

    public static Memory parse(String text) {
        String[] lines = text.split("\\R");
        Matcher key = ACCESS_KEY.matcher(text);
        Matcher rules = RULES.matcher(text);
        List<CalculationType> types = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        Map<String, BigDecimal> globals = new LinkedHashMap<>();
        Map<String, BigDecimal> current = null;
        String mercadoria = null;
        String calculation = null;
        for (String raw : lines) {
            String line = raw.strip();
            Matcher type = TYPE.matcher(line);
            if (type.matches()) {
                types.add(new CalculationType(type.group(1).strip(), type.group(2).strip(), Integer.parseInt(type.group(6)),
                        "S".equals(type.group(7)), type.group(10).strip()));
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (item.matches()) {
                if (current != null) {
                    items.add(item(mercadoria, calculation, current, globals));
                }
                current = new LinkedHashMap<>();
                mercadoria = item.group(1).strip();
                calculation = null;
                continue;
            }
            Matcher kind = ICMS_KIND.matcher(line);
            if (kind.matches() && current != null) {
                calculation = kind.group(1).strip();
                continue;
            }
            Map<String, BigDecimal> target = current != null ? current : globals;
            Matcher value = VALUE.matcher(line);
            boolean first = true;
            while (value.find()) {
                String name = value.group(1).strip();
                BigDecimal number = new BigDecimal(value.group(2));
                if (first) {
                    target.put(name, number); // the assigned one: the last assignment wins
                    first = false;
                } else {
                    target.putIfAbsent(name, number); // a value only read in an expression (AliquotaInterna...)
                }
            }
        }
        if (current != null) {
            items.add(item(mercadoria, calculation, current, globals));
        }
        return new Memory(key.find() ? key.group(1) : null, rules.find() ? rules.group(1).strip() : null, types, items, text.strip());
    }

    private static Item item(String mercadoria, String calculation, Map<String, BigDecimal> vars, Map<String, BigDecimal> globals) {
        return new Item(mercadoria, calculation,
                vars.get("ValorItemAntesDoDesconto"),
                vars.get("ValorPartidaAntecipado"),
                startingWith(vars, "CoefAgregacao"),
                vars.get("BaseCalculoItemAntecipado"),
                firstOf(vars, globals, "AliquotaInterna"),
                firstOf(vars, globals, "AliquotaInterEstadual"),
                startingWith(vars, "PercentualCargaLiquida"),
                vars.get("CreditoUtilizado"),
                vars.get("ICMSAntecipadoItem"));
    }

    private static BigDecimal firstOf(Map<String, BigDecimal> vars, Map<String, BigDecimal> globals, String name) {
        BigDecimal own = vars.get(name);
        return own != null ? own : globals.get(name);
    }

    private static BigDecimal startingWith(Map<String, BigDecimal> vars, String prefix) {
        return vars.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** The page as the browser shows it: one line per row / block, cells apart, entities decoded. */
    static String text(String html) {
        String text = html.replaceAll("(?is)<(script|style)\\b.*?</\\1>", " ")
                .replaceAll("(?i)</t[dh]>", " ")
                .replaceAll("(?i)<br\\s*/?>|</(tr|p|div|li|h\\d|table|caption|legend|fieldset)>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        StringBuilder out = new StringBuilder();
        boolean blank = false;
        for (String line : text.split("\\R")) {
            String clean = line.replaceAll("[ \\t\\x0B\\f]+", " ").strip();
            if (clean.isEmpty()) {
                if (!blank && !out.isEmpty()) {
                    out.append('\n');
                }
                blank = true;
                continue;
            }
            blank = false;
            out.append(clean).append('\n');
        }
        return out.toString().toLowerCase(Locale.ROOT).contains("cálculo de nota fiscal")
                ? out.substring(Math.max(0, out.indexOf("Cálculo de Nota Fiscal"))) : out.toString();
    }
}
