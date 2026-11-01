package app.nota.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryBuilderTest {
    @Test
    fun quotesStrippedFromTokens() {
        assertEquals("\"ab\"*", FtsQueryBuilder.build("a\"b"))
    }

    @Test
    fun quoteOnlyQueryIsNull() {
        assertNull(FtsQueryBuilder.build("\"\"\""))
        assertNull(FtsQueryBuilder.build("\""))
    }

    @Test
    fun multiTokenAndForm() {
        assertEquals("\"hello\"* \"world\"*", FtsQueryBuilder.build("hello world"))
        assertEquals("\"hello\"* \"world\"*", FtsQueryBuilder.build("  hello   world  "))
    }

    @Test
    fun blankIsNull() {
        assertNull(FtsQueryBuilder.build(""))
        assertNull(FtsQueryBuilder.build("   "))
    }
}
