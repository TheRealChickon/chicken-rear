package dev.kb896.aiusage.rear

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThreadPriorityTest {
    @Test fun `no thread priority is made by adding to a constant`() {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java").isDirectory) d = d.parentFile
        val src = File(d ?: File("."), "app/src/main/java")
        val bad = src.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> Regex("THREAD_PRIORITY_[A-Z_]+\\s*[-+]\\s*\\d").containsMatchIn(l) }
                .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }.toList()
        assertTrue("thread priorities made by arithmetic:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
