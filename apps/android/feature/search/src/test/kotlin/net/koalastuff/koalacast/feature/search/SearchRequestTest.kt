package net.koalastuff.koalacast.feature.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestTest {
    @Test
    fun `request matches query and active filters`() {
        val state = SearchUiState(
            query = "  syntax  ",
            languages = setOf("en"),
            category = "Technology",
        )

        assertTrue(
            state.matches(
                SearchRequest(
                    query = "syntax",
                    languages = setOf("en"),
                    category = "Technology",
                ),
            ),
        )
    }

    @Test
    fun `same query with changed filters is stale`() {
        val state = SearchUiState(
            query = "syntax",
            languages = setOf("de"),
            category = "Education",
        )

        assertFalse(
            state.matches(
                SearchRequest(
                    query = "syntax",
                    languages = setOf("en"),
                    category = "Education",
                ),
            ),
        )
        assertFalse(
            state.matches(
                SearchRequest(
                    query = "syntax",
                    languages = setOf("de"),
                    category = "Technology",
                ),
            ),
        )
    }
}
