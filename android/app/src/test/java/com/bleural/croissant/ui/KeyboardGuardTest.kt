package com.bleural.croissant.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No text field but the shared one (`ui/Keyboard.kt`, [OwnTextField]): any other would be
 * typed on nothing, or on the system's keyboard if it ever got past the block. The sources
 * are read as text, comments left out (block comments and `//` to the end of a line, unless
 * the `//` follows a colon or a quote, as in a URL), so a doc naming a field is no failure.
 */
class KeyboardGuardTest {
    private val root = File("src/main/java")
    private val allowed = "com/bleural/croissant/ui/Keyboard.kt"
    private val fields = Regex("""\b(BasicTextField|BasicSecureTextField|OutlinedTextField|EditText|SearchView)\b|\b(TextField|SecureTextField)\s*\(""")
    private val blocks = Regex("""/\*[\s\S]*?\*/""")
    private val lines = Regex("""(?<![:"])//.*""")

    @Test fun everyTextFieldIsTheSharedOne() {
        assertTrue("no sources under ${root.absolutePath}", root.isDirectory)
        val found = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.invariantSeparatorsPath.endsWith(allowed) }
            .flatMap { f ->
                val code = lines.replace(blocks.replace(f.readText()) { m -> "\n".repeat(m.value.count { it == '\n' }) }, "")
                code.lineSequence().withIndex()
                    .mapNotNull { (i, line) -> fields.find(line)?.let { "${f.relativeTo(root).invariantSeparatorsPath}:${i + 1}: ${it.value.trim()}" } }
            }
            .toList()
        assertTrue(
            "Text fields outside ui/Keyboard.kt; use the shared primitive OwnTextField (ui/Keyboard.kt) instead:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }
}
