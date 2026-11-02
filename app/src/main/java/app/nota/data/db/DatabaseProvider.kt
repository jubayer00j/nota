package app.nota.data.db

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the ACTIVE account's [NotaDatabase] (Phase 3a).
 *
 * Until Phase 3 the app had exactly one database (guest) and every
 * repository captured its DAOs at construction. Accounts change that:
 * the database is a function of who is signed in, and it can change at
 * runtime. Every data-layer class now resolves its DAOs through this
 * provider at call time (`provider.current.noteDao()`), never captures
 * them, so a switch is a single atomic swap here.
 *
 * The swap protocol is deliberately boring:
 * 1. Build the target database (cheap — Room opens lazily).
 * 2. Publish it (volatile write) and update [accountId].
 * 3. Close the previous instance.
 * In-flight DAO calls on the old instance finish against it (Room
 * close does not abort running queries); new calls land on the new
 * database. The UI shell is keyed by [accountId] in MainActivity, so
 * every ViewModel and flow collector is recreated against the new
 * database immediately after a switch — no collector can keep reading
 * the previous account's rows (the isolation invariant the account
 * tests pin).
 *
 * The guest database file is NEVER renamed, moved or deleted by
 * switching: signing in leaves `nota_guest` byte-for-byte untouched on
 * disk, which is what makes the guest library sacred (spec §3 + the
 * Phase 3 brief: Jubayer's real restored library lives there).
 */
class DatabaseProvider(
    context: Context,
    initialAccountId: String = NotaDatabase.GUEST_ACCOUNT,
    initialDatabase: NotaDatabase? = null,
) {
    private val appContext: Context = context.applicationContext

    @Volatile
    var current: NotaDatabase =
        initialDatabase ?: NotaDatabase.build(appContext, initialAccountId)
        private set

    private val _accountId = MutableStateFlow(initialAccountId)

    /** The account whose database is active: [NotaDatabase.GUEST_ACCOUNT] or a Firebase uid. */
    val accountId: StateFlow<String> = _accountId.asStateFlow()

    val isGuest: Boolean get() = _accountId.value == NotaDatabase.GUEST_ACCOUNT

    /**
     * Switches the active database to [accountId]'s. Returns false when
     * already there (no rebuild, no close). Synchronised so two rapid
     * sign-in/sign-out taps can never interleave a half-swapped state.
     */
    @Synchronized
    fun switchTo(accountId: String): Boolean {
        if (accountId == _accountId.value) return false
        val next = NotaDatabase.build(appContext, accountId)
        val previous = current
        current = next
        _accountId.value = accountId
        previous.close()
        return true
    }

    /**
     * Opens an ADDITIONAL database (e.g. the guest library while an
     * account is active, for the guest→account copy). The caller owns
     * the returned instance and must close it via [closeAdditional].
     * Asking for the active account returns [current] instead (never a
     * second handle on the live database) — never close that one.
     */
    fun openAdditional(accountId: String): NotaDatabase =
        if (accountId == _accountId.value) current
        else NotaDatabase.build(appContext, accountId)

    /** Closes [db] only when it is an additional instance from
     *  [openAdditional] — closing [current] would break the app. */
    fun closeAdditional(db: NotaDatabase) {
        if (db !== current) db.close()
    }

    /**
     * Account ids that have a database file on this device (guest
     * first). Discovered from the databases directory — Room database
     * files are `nota_guest` / `nota_<uid>` (plus SQLite sidecars,
     * filtered out). Used by the cross-account attachment sweep: files
     * under the shared attachments/ root belong to whichever account's
     * rows reference them, and one account's housekeeping must never
     * delete another account's binaries.
     */
    fun existingAccountIds(): List<String> {
        val dir = appContext.getDatabasePath(
            NotaDatabase.databaseName(NotaDatabase.GUEST_ACCOUNT),
        ).parentFile ?: return listOf(NotaDatabase.GUEST_ACCOUNT)
        val suffixes = dir.listFiles()
            ?.map { it.name }
            ?.filter { name ->
                name.startsWith("nota_") &&
                    !name.endsWith("-wal") && !name.endsWith("-shm") &&
                    !name.endsWith("-journal")
            }
            ?.map { it.removePrefix("nota_") }
            ?.distinct()
            ?: emptyList()
        val accounts = suffixes.map { suffix ->
            if (suffix == "guest") NotaDatabase.GUEST_ACCOUNT else suffix
        }
        return (listOf(NotaDatabase.GUEST_ACCOUNT) + accounts).distinct()
    }

    /**
     * The union of attachment localPaths across EVERY account database
     * on the device — the keep-set for the shared attachments/ file
     * sweep. The active database is read through [current]; the others
     * are opened read-and-close for this call only.
     */
    suspend fun allAttachmentLocalPaths(): Set<String> {
        val paths = mutableSetOf<String>()
        for (account in existingAccountIds()) {
            val db = openAdditional(account)
            try {
                paths += db.attachmentDao().getAll().map { it.localPath }
            } finally {
                closeAdditional(db)
            }
        }
        return paths
    }

    companion object {
        /** Test seam: a provider whose [current] is a prebuilt database
         *  (Robolectric tests build in-memory/temporary databases and
         *  hand them over). Production code never uses this. */
        fun forDatabase(context: Context, db: NotaDatabase): DatabaseProvider =
            DatabaseProvider(context, NotaDatabase.GUEST_ACCOUNT, db)
    }
}
