package dev.kb896.aiusage;

/**
 * The guard: a second, tiny shell process that does one thing, keep Xiaomi's rear screen covered
 * (rear/RearGuardService). Nothing is asked of it but that it be alive.
 */
interface IRearGuard {
    // Shizuku's own: the process is to end.
    void destroy() = 16777114;
    boolean alive() = 1;
}
