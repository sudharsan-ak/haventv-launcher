package com.aktvhome.launcher.core.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;

/**
 * Search ranking for Haven's search screen: which documents match a query, best first.
 *
 * Every word of the query has to match somewhere; each word scores by where and how well it
 * matches (the whole name, the start of the name or a word in it, anywhere in the name, then the
 * subtitle, description and keywords), and the scores add up. Ties go alphabetically by name.
 *
 * Typing on a TV remote is slow, so when nothing matches exactly the query is tried again
 * allowing typos in words of 4 or more letters: one for 4 to 7 letters, two for 8 or more, with
 * two swapped letters counting as one ("netlfix" finds Netflix). When anything matches exactly,
 * only exact matches are returned, so a query typed correctly never picks up near misses.
 */
public final class SearchMatcher {

    private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9]+");

    private static final int NAME_EXACT = 120;
    private static final int NAME_PREFIX = 80;
    private static final int NAME_WORD_PREFIX = 65;
    private static final int NAME_CONTAINS = 50;
    private static final int OTHER_WORD_PREFIX = 24;
    private static final int OTHER_CONTAINS = 16;
    private static final int NAME_TYPO = 12;
    private static final int OTHER_TYPO = 6;

    /** Words shorter than this need an exact match: with fewer letters, a typo match is a guess. */
    static final int MIN_TYPO_LENGTH = 4;

    /** From this length a word may have two typos. */
    static final int TWO_TYPO_LENGTH = 8;

    private SearchMatcher() {
    }

    /** Every match, best first. */
    @NotNull
    public static List<SearchDocument> ranked(@NotNull List<SearchDocument> documents, @NotNull String query) {
        return ranked(documents, query, Integer.MAX_VALUE);
    }

    /**
     * The best {@code limit} matches for {@code query}, best first. Documents with the same id
     * count once (the first one). A blank query matches everything, in the order given. The list
     * returned is read-only.
     */
    @NotNull
    public static List<SearchDocument> ranked(@NotNull List<SearchDocument> documents, @NotNull String query, int limit) {
        int max = Math.max(limit, 0);
        Map<String, SearchDocument> unique = new LinkedHashMap<>();
        for (SearchDocument d : documents) unique.putIfAbsent(d.getId(), d);
        List<String> words = words(query);
        if (words.isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<>(unique.values()).subList(0, Math.min(max, unique.size())));
        }

        List<Scored> matches = matches(unique.values(), words, false);
        if (matches.isEmpty()) matches = matches(unique.values(), words, true);
        matches.sort(Comparator.comparingInt((Scored s) -> s.score).reversed()
            .thenComparing(s -> s.document.getName().toLowerCase(Locale.ROOT)));

        List<SearchDocument> out = new ArrayList<>(Math.min(max, matches.size()));
        for (int i = 0; i < matches.size() && i < max; i++) out.add(matches.get(i).document);
        return Collections.unmodifiableList(out);
    }

    private static List<Scored> matches(Iterable<SearchDocument> documents, List<String> words, boolean typos) {
        List<Scored> out = new ArrayList<>();
        for (SearchDocument d : documents) {
            int score = score(d, words, typos);
            if (score > 0) out.add(new Scored(d, score));
        }
        return out;
    }

    /** The document's score for {@code words}, or 0 when a word matches nowhere. */
    private static int score(SearchDocument d, List<String> words, boolean typos) {
        String name = normalize(d.getName());
        String other = normalize(d.getSubtitle() + " " + d.getDescription() + " " + d.getKeywords());
        String[] nameWords = name.split(" ");
        String[] otherWords = other.split(" ");
        int total = 0;
        for (String word : words) {
            int s;
            if (name.equals(word)) s = NAME_EXACT;
            else if (name.startsWith(word)) s = NAME_PREFIX;
            else if (anyStartsWith(nameWords, word)) s = NAME_WORD_PREFIX;
            else if (name.contains(word)) s = NAME_CONTAINS;
            else if (anyStartsWith(otherWords, word)) s = OTHER_WORD_PREFIX;
            else if (other.contains(word)) s = OTHER_CONTAINS;
            else if (typos && anyNearly(nameWords, word)) s = NAME_TYPO;
            else if (typos && anyNearly(otherWords, word)) s = OTHER_TYPO;
            else return 0;
            total += s;
        }
        return total;
    }

    private static boolean anyStartsWith(String[] candidates, String word) {
        for (String c : candidates) if (c.startsWith(word)) return true;
        return false;
    }

    /** Whether {@code word} is within its typo allowance of any candidate, or of the start of one (still typing). */
    private static boolean anyNearly(String[] candidates, String word) {
        int allowed = typosAllowed(word);
        if (allowed == 0) return false;
        for (String c : candidates) {
            if (c.isEmpty()) continue;
            if (Typos.distance(word, c) <= allowed) return true;
            for (int len = word.length() - allowed; len <= word.length() + allowed; len++) {
                if (len >= MIN_TYPO_LENGTH && len < c.length() && Typos.distance(word, c.substring(0, len)) <= allowed) return true;
            }
        }
        return false;
    }

    static int typosAllowed(String word) {
        if (word.length() < MIN_TYPO_LENGTH) return 0;
        return word.length() < TWO_TYPO_LENGTH ? 1 : 2;
    }

    private static List<String> words(String query) {
        List<String> out = new ArrayList<>();
        for (String w : normalize(query).split(" ")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** Lower case, with anything but letters and digits turned into single spaces. */
    static String normalize(String value) {
        return NOT_WORD.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static final class Scored {
        final SearchDocument document;
        final int score;

        Scored(SearchDocument document, int score) {
            this.document = document;
            this.score = score;
        }
    }
}
