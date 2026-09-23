package astana.innovation.backendakim.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Accepts LLM prose only when every number and measure it names can be traced to the facts sent to the model.
 * Anything else falls back to the template explanation, so a confident but wrong number never reaches the user.
 */
final class SimulationLlmTextGuard {
    static final int MAX_LENGTH = 5000;
    /** Counts such as "5 мер" or "3 квартала" are always allowed. */
    static final int MAX_FREE_INTEGER = 10;
    /** Rounding the model may apply to a fact: 56.54 → 56.5 → 57. */
    static final int MAX_ROUNDING_SCALE = 2;

    // Digits glued to letters are identifiers (M7, T1, S2), not numeric claims.
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}_.,])\\d+(?:[.,]\\d+)?");
    // Latin M or Cyrillic М followed by a number: M7, М12.
    private static final Pattern MEASURE = Pattern.compile("(?<![\\p{L}\\p{N}_])[MМ](\\d{1,3})(?![\\p{N}.,]\\d)");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
    private static final Pattern LATIN = Pattern.compile("\\p{IsLatin}");

    private SimulationLlmTextGuard() { }

    /** @return why the text must be rejected, or empty when every checked claim is grounded in the facts. */
    static Optional<String> rejection(String text, JsonNode facts, Set<String> measureIds) {
        if (text.length() > MAX_LENGTH) return Optional.of("longer than " + MAX_LENGTH + " characters");
        String trimmed = text.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || text.contains("```")) {
            return Optional.of("JSON or code instead of prose");
        }
        long cyrillic = CYRILLIC.matcher(text).results().count();
        if (cyrillic == 0 || cyrillic < LATIN.matcher(text).results().count()) return Optional.of("not Russian prose");

        Matcher measure = MEASURE.matcher(text);
        while (measure.find()) {
            String id = "M" + measure.group(1);
            if (!measureIds.contains(id)) return Optional.of("measure " + id + " is not in the compared plans");
        }

        Set<BigDecimal> allowed = allowedNumbers(facts);
        Matcher number = NUMBER.matcher(text);
        while (number.find()) {
            if (!allowed.contains(canonical(new BigDecimal(number.group().replace(',', '.'))))) {
                return Optional.of("number " + number.group() + " is not in the facts");
            }
        }
        return Optional.empty();
    }

    static Set<BigDecimal> allowedNumbers(JsonNode facts) {
        Set<BigDecimal> allowed = new HashSet<>();
        for (int i = 0; i <= MAX_FREE_INTEGER; i++) allowed.add(BigDecimal.valueOf(i));
        collect(facts, allowed);
        return allowed;
    }

    private static void collect(JsonNode node, Set<BigDecimal> allowed) {
        if (node.isNumber()) {
            // Signs are not compared: "снижение на 1.75" describes a change of −1.75.
            BigDecimal value = node.decimalValue().abs();
            allowed.add(canonical(value));
            for (int scale = 0; scale <= MAX_ROUNDING_SCALE; scale++) {
                allowed.add(canonical(value.setScale(scale, RoundingMode.HALF_UP)));
            }
        } else if (node.isString()) {
            NUMBER.matcher(node.asString()).results()
                    .forEach(m -> allowed.add(canonical(new BigDecimal(m.group().replace(',', '.')))));
        } else {
            node.values().forEach(child -> collect(child, allowed));
        }
    }

    private static BigDecimal canonical(BigDecimal value) {
        return new BigDecimal(value.stripTrailingZeros().toPlainString());
    }
}
