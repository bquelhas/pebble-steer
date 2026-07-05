package com.bquelhas.steer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved destination the user can tap to start navigation.
 *
 * [icon] is the id from the shared `fav_icons.tsv` manifest (0 = generic pin / none). It travels to
 * the watch as NAV_FAV_ICON so the watch menu can draw the matching glyph. Defaults to 0 for
 * favourites saved before the icon picker existed.
 */
data class Favorite(val label: String, val query: String, val icon: Int = 0) {
    fun toJson(): JSONObject =
        JSONObject().put("label", label).put("query", query).put("icon", icon)

    companion object {
        fun fromJson(o: JSONObject) =
            Favorite(o.optString("label"), o.optString("query"), o.optInt("icon", 0))
    }
}

/**
 * Persists the user's favourite destinations as a JSON array in SharedPreferences.
 * Kept deliberately small (no DB): a handful of destinations is all this needs.
 */
object FavoritesStore {
    private const val FILE = "navme_prefs"
    private const val KEY = "favorites"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(context: Context): MutableList<Favorite> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { Favorite.fromJson(arr.getJSONObject(it)) }
    }

    private fun save(context: Context, list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, fav: Favorite) {
        val list = all(context)
        list.add(fav)
        save(context, list)
    }

    fun update(context: Context, index: Int, fav: Favorite) {
        val list = all(context)
        if (index in list.indices) {
            list[index] = fav
            save(context, list)
        }
    }

    fun removeAt(context: Context, index: Int) {
        val list = all(context)
        if (index in list.indices) {
            list.removeAt(index)
            save(context, list)
        }
    }
}
