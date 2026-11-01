package com.bleural.croissant.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * The settings file's values with their types, as prefs.json holds them, so each goes back
 * as the type it was: `{"volume": {"type": "int", "value": 40}}`. Types: string, int, long,
 * float, boolean, strings (a string set, as a JSON array).
 */
object TypedPrefs {
    private const val STRING = "string"
    private const val INT = "int"
    private const val LONG = "long"
    private const val FLOAT = "float"
    private const val BOOLEAN = "boolean"
    private const val STRINGS = "strings"

    /** Throws [IOException] for a value SharedPreferences cannot hold, naming its key. */
    fun encode(values: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in values.toSortedMap()) {
            val (type, value) = when (v) {
                null -> continue
                is String -> STRING to v
                is Int -> INT to v
                is Long -> LONG to v
                is Float -> FLOAT to v
                is Boolean -> BOOLEAN to v
                is Set<*> -> STRINGS to JSONArray(v.map { it as? String ?: throw IOException("the setting $k holds a set with a ${it?.javaClass?.simpleName}") }.sorted())
                else -> throw IOException("the setting $k holds a ${v.javaClass.simpleName}, which a backup cannot carry")
            }
            o.put(k, JSONObject().put("type", type).put("value", value))
        }
        return o
    }

    /** Throws [Unfit] naming the key whose type or value is not one a setting has. */
    fun decode(o: JSONObject): Map<String, Any> = o.keys().asSequence().associateWith { k ->
        val e = o.opt(k) as? JSONObject ?: throw Unfit("prefs.json: $k is not a typed value")
        val type = e.opt("type") as? String ?: throw Unfit("prefs.json: $k has no type")
        val v = e.opt("value")
        fun bad(): Nothing = throw Unfit("prefs.json: $k is a $type with the value ${v?.toString()?.take(40)}")
        when (type) {
            STRING -> v as? String ?: bad()
            INT -> Json.integral(v)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: bad()
            LONG -> Json.integral(v) ?: bad()
            FLOAT -> (v as? Number)?.toFloat() ?: bad()
            BOOLEAN -> v as? Boolean ?: bad()
            STRINGS -> (v as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) as? String ?: bad() }.toSet() } ?: bad()
            else -> throw Unfit("prefs.json: $k has the type $type, which no setting has")
        }
    }
}
