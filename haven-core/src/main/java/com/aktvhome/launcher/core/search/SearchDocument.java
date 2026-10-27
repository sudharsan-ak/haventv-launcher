package com.aktvhome.launcher.core.search;

import java.util.Objects;

import org.jetbrains.annotations.NotNull;

/**
 * One thing search can find, as plain text: its id (unique across all results), the name shown
 * in the list, a subtitle and description, and extra keywords that match but aren't shown.
 * Immutable. Kotlin reads the getters as properties ({@code document.name}).
 */
public final class SearchDocument {

    private final String id;
    private final String name;
    private final String subtitle;
    private final String description;
    private final String keywords;

    public SearchDocument(
        @NotNull String id,
        @NotNull String name,
        @NotNull String subtitle,
        @NotNull String description,
        @NotNull String keywords
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.subtitle = Objects.requireNonNull(subtitle, "subtitle");
        this.description = Objects.requireNonNull(description, "description");
        this.keywords = Objects.requireNonNull(keywords, "keywords");
    }

    /** Java has no default arguments, so the short forms leave the rest blank. */
    public SearchDocument(@NotNull String id, @NotNull String name, @NotNull String subtitle) {
        this(id, name, subtitle, "", "");
    }

    public SearchDocument(@NotNull String id, @NotNull String name) {
        this(id, name, "", "", "");
    }

    @NotNull
    public String getId() {
        return id;
    }

    @NotNull
    public String getName() {
        return name;
    }

    @NotNull
    public String getSubtitle() {
        return subtitle;
    }

    @NotNull
    public String getDescription() {
        return description;
    }

    @NotNull
    public String getKeywords() {
        return keywords;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SearchDocument)) return false;
        SearchDocument d = (SearchDocument) o;
        return id.equals(d.id) && name.equals(d.name) && subtitle.equals(d.subtitle)
            && description.equals(d.description) && keywords.equals(d.keywords);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, subtitle, description, keywords);
    }

    @Override
    public String toString() {
        return "SearchDocument(id=" + id + ", name=" + name + ")";
    }
}
