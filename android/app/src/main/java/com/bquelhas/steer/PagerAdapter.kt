package com.bquelhas.steer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Three static, non-fragment pages for the ViewPager2 (Home / Favourites /
 * Developer). Home merges the old status/preview screen with the customization
 * controls. Each page is a distinct view type, so the pager keeps them alive and
 * never swaps their contents. [onBindPage] hands the inflated root back to
 * [MainActivity], which wires up the views for that page.
 */
class PagerAdapter(
    private val onBindPage: (position: Int, root: View) -> Unit
) : RecyclerView.Adapter<PagerAdapter.PageVH>() {

    class PageVH(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = 3

    override fun getItemViewType(position: Int) = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val layout = when (viewType) {
            0 -> R.layout.page_home
            1 -> R.layout.page_favourites
            else -> R.layout.page_developer
        }
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        v.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.MATCH_PARENT
        )
        return PageVH(v)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        onBindPage(position, holder.itemView)
    }
}
