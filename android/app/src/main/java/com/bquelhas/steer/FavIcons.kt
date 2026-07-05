package com.bquelhas.steer

import android.content.Context

/**
 * The shared favorite-icon manifest (`assets/fav_icons.tsv`, generated on the watch side and copied
 * here verbatim). Each entry maps an icon [id] — the number sent to the watch as NAV_FAV_ICON — to
 * its Android drawable ([resName], e.g. `ic_fav_home`, resolved at runtime). Id 0 is the generic pin
 * fallback used by favourites saved before an icon was chosen.
 *
 * If the manifest is ever regenerated on the watch side, recopy it into `assets/` so both sides stay
 * in sync (the ids must line up — the watch draws by id).
 */
object FavIcons {
    data class Icon(val id: Int, val name: String, val resName: String)

    private const val ASSET = "fav_icons.tsv"
    private const val GENERIC_PIN_ID = 0

    @Volatile private var cache: List<Icon>? = null

    /** All icons in manifest order (id-ascending). Parsed once and cached. */
    fun all(context: Context): List<Icon> {
        cache?.let { return it }
        val list = mutableListOf<Icon>()
        context.applicationContext.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val cols = line.split('\t')
                if (cols.size >= 3) {
                    val id = cols[0].trim().toIntOrNull() ?: return@forEach
                    list.add(Icon(id, cols[1].trim(), cols[2].trim()))
                }
            }
        }
        return list.sortedBy { it.id }.also { cache = it }
    }

    fun byId(context: Context, id: Int): Icon? = all(context).firstOrNull { it.id == id }

    /**
     * Resolves the drawable resource for [id], falling back to the generic pin (id 0) and finally to
     * a system pin so a stale/unknown id never crashes. Returns 0 only if nothing resolves.
     */
    fun drawableRes(context: Context, id: Int): Int {
        val ctx = context.applicationContext
        val name = (byId(ctx, id) ?: byId(ctx, GENERIC_PIN_ID))?.resName ?: return 0
        val res = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        return if (res != 0) res else android.R.drawable.ic_menu_mylocation
    }
}
