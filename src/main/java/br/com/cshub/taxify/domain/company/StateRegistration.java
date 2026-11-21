package br.com.cshub.taxify.domain.company;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** Inscrição estadual of a company in one UF - stored on the company as "IE/UF; IE/UF". */
public final class StateRegistration {

    private final String uf;
    private final String number;

    public StateRegistration(String uf, String number) {
        this.uf = uf;
        this.number = number;
    }

    /** Null when either part is missing; "ISENTO" is kept as is, otherwise only digits. */
    public static StateRegistration of(String uf, String number) {
        if (uf == null || uf.isBlank() || number == null || number.isBlank()) {
            return null;
        }
        String normalizedNumber = number.strip().toUpperCase(Locale.ROOT).startsWith("ISENT") ? "ISENTO" : number.replaceAll("\\D", "");
        if (normalizedNumber.isEmpty()) {
            return null;
        }
        return new StateRegistration(uf.strip().toUpperCase(Locale.ROOT), normalizedNumber.length() > 20 ? normalizedNumber.substring(0, 20) : normalizedNumber);
    }

    /** "123456/PE; 98765/SP" → registrations; entries without a UF are skipped (legacy text without one is lost on save only if edited). */
    public static List<StateRegistration> parse(String text) {
        List<StateRegistration> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (String entry : text.split(";")) {
            int slash = entry.lastIndexOf('/');
            if (slash > 0) {
                StateRegistration registration = of(entry.substring(slash + 1), entry.substring(0, slash));
                if (registration != null && !result.contains(registration)) {
                    result.add(registration);
                }
            }
        }
        return result;
    }

    public static String format(Collection<StateRegistration> registrations) {
        if (registrations == null || registrations.isEmpty()) {
            return null;
        }
        String text = registrations.stream().map(r -> r.number + "/" + r.uf).collect(Collectors.joining("; "));
        return text.length() > 255 ? text.substring(0, 255) : text;
    }

    public String getUf() {
        return uf;
    }

    public String getNumber() {
        return number;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StateRegistration other && Objects.equals(uf, other.uf) && Objects.equals(number, other.number);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uf, number);
    }
}
