package ai.generativearchitects.slay400.gateway.ibmi;

import ai.generativearchitects.slay400.gateway.config.IbmiSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes the host, the user, the password and the libraries of an environment out of text that came from the IBM i, such as the
 * first-level text of a message, before it reaches the operator's log: the code never prints them (kickoff 03).
 */
final class Redactor {

    private static final String NAME_CHAR = "A-Za-z0-9$#@_";
    private final List<Map.Entry<Pattern, String>> replacements = new ArrayList<>();

    Redactor(IbmiSettings settings) {
        Map<String, String> words = new LinkedHashMap<>();
        char[] password = settings.credentials().password();
        try {
            if (password.length > 0) {
                words.put(new String(password), "<password>");
            }
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        words.put(settings.host(), "<host>");
        words.put(settings.credentials().user(), "<user>");
        settings.libraryList().forEach(library -> words.putIfAbsent(library, "<library>"));
        words.entrySet().stream().filter(e -> !e.getKey().isEmpty()).sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
                .forEach(e -> replacements.add(Map.entry(Pattern.compile("(?<![" + NAME_CHAR + "])" + Pattern.quote(e.getKey()) + "(?![" + NAME_CHAR + "])",
                        Pattern.CASE_INSENSITIVE), e.getValue())));
    }

    /** The text with every occurrence of the host, the user, the password and the libraries replaced. */
    String apply(String text) {
        String out = text;
        for (Map.Entry<Pattern, String> replacement : replacements) {
            out = replacement.getKey().matcher(out).replaceAll(Matcher.quoteReplacement(replacement.getValue()));
        }
        return out;
    }
}
