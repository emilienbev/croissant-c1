package com.bleural.croissant.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Lucide's icons (lucide.dev, ISC; NOTICE), which an Atelier app names as its glyph in
 * croissant.json. The asset is written by scripts/lucide.py from a pinned release: the same
 * 24-unit grid and 2-unit round strokes as Croissant's own glyphs, drawn the same way
 * ([glyph]), so an app's glyph sits among the features' as one of theirs.
 */
object Lucide {
    private const val ASSET = "lucide/icons.json"

    @Volatile private var paths: Map<String, List<String>>? = null
    private val built = ConcurrentHashMap<String, ImageVector>()

    /** [name]'s glyph, or null when Lucide has no such icon. Reads the asset the first time: not on the main thread. */
    fun glyph(context: Context, name: String): ImageVector? {
        built[name]?.let { return it }
        val p = load(context)[name] ?: return null
        return glyph("lucide-$name") {
            p.forEach { d -> if (d.startsWith("f:")) fill(d.substring(2)) else stroke(d) }
        }.also { built[name] = it }
    }

    /** Already built, without reading anything. */
    fun cached(name: String): ImageVector? = built[name]

    private fun load(context: Context): Map<String, List<String>> = paths ?: synchronized(this) {
        paths ?: run {
            val o = context.assets.open(ASSET).bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("icons")
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                List(a.length()) { a.getString(it) }
            }
        }.also { paths = it }
    }
}

/**
 * An app's glyph: the Lucide icon it names, or Atelier's pieces when it names none or one
 * Lucide does not have. Null only while the icons are read, the first time.
 */
@Composable
fun rememberAppGlyph(name: String?): ImageVector? {
    val context = LocalContext.current
    val glyph by produceState(name?.let(Lucide::cached) ?: if (name == null) Glyphs.Atelier else null, name) {
        if (name != null && value == null) {
            value = withContext(Dispatchers.Default) { Lucide.glyph(context, name) } ?: Glyphs.Atelier
        }
    }
    return glyph
}
