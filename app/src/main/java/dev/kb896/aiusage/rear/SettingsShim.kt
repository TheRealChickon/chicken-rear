package dev.kb896.aiusage.rear

/**
 * THE SETTINGS A XIAOMI FACE MAY READ, AND NOTHING IT MAY WRITE (SPEC 4.2). MAML reads Settings through the system's
 * settings provider; a shell process gets that provider by `getContentProviderExternal("settings")`, and THAT ONE CAN
 * WRITE SETTINGS AS SHELL. So what MAML is handed is a proxy of it that passes reads and refuses everything else, with
 * a log line: [allows] is the whole rule, and a test holds it.
 *
 * Pure here; the proxy itself is MamlHost's.
 */
object SettingsShim {
    /** IContentProvider's methods that only read. */
    private val READS = setOf("query", "getType", "getTypeAsync", "getStreamTypes", "canonicalize", "canonicalizeAsync", "uncanonicalize", "uncanonicalizeAsync", "asBinder", "hashCode", "equals", "toString")

    /** Of `call`'s methods (Settings.CALL_METHOD_*), the reads: GET_SYSTEM, GET_SECURE, GET_GLOBAL, GET_CONFIG, LIST_*. */
    private val CALL_READS = Regex("^(GET|LIST)_[A-Za-z_]+$")

    /**
     * Whether the provider's [method] may be passed, with [callMethod] the `call`'s method name (its first String
     * argument that looks like one) when [method] is "call".
     */
    fun allows(method: String, callMethod: String?): Boolean = when (method) {
        in READS -> true
        "call" -> callMethod != null && CALL_READS.matches(callMethod)
        else -> false
    }

    /** Of a `call`'s arguments, its method name (Settings' own are like GET_secure, PUT_global: the upper-case form decides). */
    fun callMethodOf(args: Array<out Any?>?): String? =
        args.orEmpty().filterIsInstance<String>().firstOrNull { it.contains('_') && it.substringBefore('_').let { p -> p.isNotEmpty() && p.all { c -> c.isUpperCase() } } }
}
