package com.aktvhome.launcher.data

/** Plain search text, kept Android-free so ranking stays easy to test. */
data class SearchDocument(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val description: String = "",
    val keywords: String = "",
)

object SearchMatcher {
    fun ranked(documents: List<SearchDocument>, query: String): List<SearchDocument> {
        val uniqueDocuments = documents.distinctBy { it.id }
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return uniqueDocuments
        return uniqueDocuments.mapNotNull { document ->
            score(document, words)?.let { document to it }
        }.sortedWith(compareByDescending<Pair<SearchDocument, Int>> { it.second }.thenBy { it.first.name.lowercase() })
            .map { it.first }
    }

    private fun score(document: SearchDocument, words: List<String>): Int? {
        val name = normalize(document.name)
        val other = normalize("${document.subtitle} ${document.description} ${document.keywords}")
        var total = 0
        for (word in words) {
            total += when {
                name == word -> 120
                name.startsWith(word) -> 80
                name.split(' ').any { it.startsWith(word) } -> 65
                name.contains(word) -> 50
                other.split(' ').any { it.startsWith(word) } -> 24
                other.contains(word) -> 16
                else -> return null
            }
        }
        return total
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
