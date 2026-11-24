package br.com.cshub.taxify.service.fronteira;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The accountant's own ICMS formula for the Fronteira PE: plain arithmetic over the item's values,
 * read by a small parser of its own - no script engine, nothing else can run. Accepts numbers
 * ({@code 0,055} or {@code 0.055}), {@code 5,5%} (= 0,055), the variables in {@link #VARIABLES},
 * {@code + - * /}, parentheses and the functions {@code min(a; b)}, {@code max(a; b)} and
 * {@code arred(x)} (2 places, half up). Arguments are split by {@code ;} so the comma stays the
 * decimal mark. Rates come in as fractions: interna 20,5% is 0,205.
 */
public final class FormulaEvaluator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Variable → what it is (shown on the screen next to the formula field). */
    public static final Map<String, String> VARIABLES;

    static {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("partida", "Valor de partida do item: produto + IPI + frete, seguro e outras despesas rateados (o desconto não reduz)");
        variables.put("valor_produto", "Valor do produto no item (vProd)");
        variables.put("ipi", "IPI do item");
        variables.put("despesas", "Frete, seguro e outras despesas da nota rateados para o item");
        variables.put("icms_origem", "ICMS destacado no item pelo fornecedor");
        variables.put("aliquota_origem", "Alíquota de ICMS destacada no item (fração: 12% = 0,12)");
        variables.put("inter", "Alíquota interestadual pela origem e pela UF (fração: 4%, 7% ou 12%)");
        variables.put("interna", "Alíquota interna de PE da tabela de ICMS (fração: 20,5% = 0,205)");
        variables.put("mva", "MVA original da regra de ST do item (fração: 54% = 0,54; zero sem regra)");
        variables.put("mva_ajustada", "MVA ajustada: [(1 + mva) × (1 − inter) ÷ (1 − interna)] − 1");
        variables.put("st_retido", "ICMS-ST já retido pelo fornecedor no item");
        variables.put("percentual", "Percentual do art. 363-A da regra do Simples (fração: 5,5% = 0,055; zero sem regra)");
        VARIABLES = java.util.Collections.unmodifiableMap(variables);
    }

    private static final Set<String> FUNCTIONS = Set.of("min", "max", "arred");

    public static class FormulaException extends RuntimeException {
        public FormulaException(String message) {
            super(message);
        }
    }

    private final Map<String, BigDecimal> values;
    private final List<String> tokens;
    private int position;

    private FormulaEvaluator(String text, Map<String, BigDecimal> values) {
        this.values = values;
        this.tokens = tokenize(text);
    }

    /** The formula's value with these variables; throws {@link FormulaException} with a message for people. */
    public static BigDecimal evaluate(String formula, Map<String, BigDecimal> values) {
        if (formula == null || formula.isBlank()) {
            throw new FormulaException("A fórmula está vazia.");
        }
        if (formula.length() > 500) {
            throw new FormulaException("A fórmula passa de 500 caracteres.");
        }
        FormulaEvaluator evaluator = new FormulaEvaluator(formula, values);
        BigDecimal result = evaluator.expression();
        if (evaluator.position < evaluator.tokens.size()) {
            throw new FormulaException("Sobrou \"" + evaluator.tokens.get(evaluator.position) + "\" no fim da fórmula.");
        }
        return result;
    }

    /** Checks the formula with every variable at a sample value - for saving it. */
    public static void validate(String formula) {
        Map<String, BigDecimal> sample = new LinkedHashMap<>();
        VARIABLES.keySet().forEach(name -> sample.put(name, new BigDecimal("0.1")));
        sample.put("partida", new BigDecimal("1000"));
        evaluate(formula, sample);
    }

    /** The variables the formula uses (for the calculation memory). */
    public static Set<String> variablesIn(String formula) {
        Set<String> used = new TreeSet<>();
        for (String token : tokenize(formula == null ? "" : formula)) {
            String name = token.toLowerCase(Locale.ROOT);
            if (VARIABLES.containsKey(name)) {
                used.add(name);
            }
        }
        return used;
    }

    // expression := term (('+' | '-') term)*
    private BigDecimal expression() {
        BigDecimal value = term();
        while (peek("+") || peek("-")) {
            String operator = next();
            BigDecimal right = term();
            value = "+".equals(operator) ? value.add(right, MC) : value.subtract(right, MC);
        }
        return value;
    }

    // term := factor (('*' | '/') factor)*
    private BigDecimal term() {
        BigDecimal value = factor();
        while (peek("*") || peek("/")) {
            String operator = next();
            BigDecimal right = factor();
            if ("*".equals(operator)) {
                value = value.multiply(right, MC);
            } else {
                if (right.signum() == 0) {
                    throw new FormulaException("Divisão por zero na fórmula.");
                }
                value = value.divide(right, MC);
            }
        }
        return value;
    }

    // factor := ('+' | '-') factor | primary '%'?
    private BigDecimal factor() {
        if (peek("-")) {
            next();
            return factor().negate();
        }
        if (peek("+")) {
            next();
            return factor();
        }
        BigDecimal value = primary();
        if (peek("%")) {
            next();
            value = value.divide(HUNDRED, MC);
        }
        return value;
    }

    // primary := number | variable | function '(' expression (';' expression)* ')' | '(' expression ')'
    private BigDecimal primary() {
        if (position >= tokens.size()) {
            throw new FormulaException("A fórmula termina no meio de uma conta.");
        }
        String token = next();
        if ("(".equals(token)) {
            BigDecimal value = expression();
            expect(")");
            return value;
        }
        if (Character.isDigit(token.charAt(0))) {
            return new BigDecimal(token.replace(',', '.'));
        }
        if (Character.isLetter(token.charAt(0)) || token.charAt(0) == '_') {
            String name = token.toLowerCase(Locale.ROOT);
            if (FUNCTIONS.contains(name)) {
                expect("(");
                List<BigDecimal> arguments = new ArrayList<>();
                arguments.add(expression());
                while (peek(";")) {
                    next();
                    arguments.add(expression());
                }
                expect(")");
                return call(name, arguments);
            }
            if (!VARIABLES.containsKey(name)) {
                throw new FormulaException("\"" + token + "\" não é uma variável conhecida. Use: " + String.join(", ", VARIABLES.keySet()) + ".");
            }
            BigDecimal value = values.get(name);
            return value == null ? BigDecimal.ZERO : value;
        }
        throw new FormulaException("\"" + token + "\" fora do lugar na fórmula.");
    }

    private static BigDecimal call(String name, List<BigDecimal> arguments) {
        return switch (name) {
            case "arred" -> {
                if (arguments.size() != 1) {
                    throw new FormulaException("arred(x) recebe um valor só.");
                }
                yield arguments.get(0).setScale(2, RoundingMode.HALF_UP);
            }
            case "min" -> {
                if (arguments.size() < 2) {
                    throw new FormulaException("min(a; b) recebe pelo menos dois valores.");
                }
                yield arguments.stream().min(BigDecimal::compareTo).orElseThrow();
            }
            default -> {
                if (arguments.size() < 2) {
                    throw new FormulaException("max(a; b) recebe pelo menos dois valores.");
                }
                yield arguments.stream().max(BigDecimal::compareTo).orElseThrow();
            }
        };
    }

    private boolean peek(String token) {
        return position < tokens.size() && tokens.get(position).equals(token);
    }

    private String next() {
        return tokens.get(position++);
    }

    private void expect(String token) {
        if (!peek(token)) {
            throw new FormulaException("Faltou \"" + token + "\" na fórmula.");
        }
        next();
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c)) {
                int start = i;
                boolean separator = false;
                while (i < text.length() && (Character.isDigit(text.charAt(i)) || (!separator && (text.charAt(i) == ',' || text.charAt(i) == '.')
                        && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))))) {
                    if (!Character.isDigit(text.charAt(i))) {
                        separator = true;
                    }
                    i++;
                }
                tokens.add(text.substring(start, i));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(text.substring(start, i));
            } else if ("+-*/()%;×÷".indexOf(c) >= 0) {
                tokens.add(c == '×' ? "*" : c == '÷' ? "/" : String.valueOf(c));
                i++;
            } else {
                throw new FormulaException("Caractere \"" + c + "\" não é aceito na fórmula.");
            }
        }
        return tokens;
    }
}
