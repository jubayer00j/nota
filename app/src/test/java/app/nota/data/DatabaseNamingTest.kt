package app.nota.data

import app.nota.data.db.NotaDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/** Per-account database naming (spec §3): guest gets its own file; accounts are isolated. */
class DatabaseNamingTest {
    @Test
    fun guestDatabaseName() {
        assertEquals("nota_guest", NotaDatabase.databaseName(NotaDatabase.GUEST_ACCOUNT))
    }

    @Test
    fun accountDatabaseName() {
        assertEquals("nota_abc123", NotaDatabase.databaseName("abc123"))
    }
}
