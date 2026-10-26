package com.hugo.tcgscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) {
            prefs.edit().putString("token", v.trim()).apply()
        }

    var translateOn: Boolean
        get() = prefs.getBoolean("translate", true)
        set(v) {
            prefs.edit().putBoolean("translate", v).apply()
        }

    private fun readArray(name: String): JSONArray {
        val f = File(ctx.filesDir, name)
        if (!f.exists()) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeArray(name: String, items: List<JSONObject>) {
        val tmp = File(ctx.filesDir, "$name.tmp")
        tmp.writeText(JSONArray(items).toString())
        tmp.renameTo(File(ctx.filesDir, name))
    }

    fun loadCatalog(): List<Blueprint> {
        val arr = readArray("catalog.json")
        return (0 until arr.length()).mapNotNull {
            try {
                blueprintFrom(arr.getJSONObject(it))
            } catch (e: Exception) {
                null
            }
        }
    }

    fun saveCatalog(list: List<Blueprint>) = writeArray("catalog.json", list.map { it.toJson() })

    fun loadCollection(): List<CollectionCard> {
        val arr = readArray("collection.json")
        return (0 until arr.length()).mapNotNull {
            try {
                collectionCardFrom(arr.getJSONObject(it))
            } catch (e: Exception) {
                null
            }
        }
    }

    fun saveCollection(list: List<CollectionCard>) = writeArray("collection.json", list.map { it.toJson() })
}
