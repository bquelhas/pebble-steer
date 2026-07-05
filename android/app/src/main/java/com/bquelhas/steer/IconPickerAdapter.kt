package com.bquelhas.steer

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid of all favourite icons for the picker dialog. Each cell is a square [ImageView]; the
 * currently selected id gets a tinted ring. Tapping a cell reports its id back via [onPick].
 */
class IconPickerAdapter(
    context: Context,
    private var selectedId: Int,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<IconPickerAdapter.IconVH>() {

    private val icons = FavIcons.all(context)

    class IconVH(val frame: FrameLayout, val image: ImageView) : RecyclerView.ViewHolder(frame)

    override fun getItemCount() = icons.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconVH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val cell = (56 * d).toInt()
        val pad = (8 * d).toInt()
        val frame = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(cell, cell)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            foreground = null
        }
        val image = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        frame.addView(image)
        return IconVH(frame, image)
    }

    override fun onBindViewHolder(holder: IconVH, position: Int) {
        val icon = icons[position]
        holder.image.setImageResource(FavIcons.drawableRes(holder.frame.context, icon.id))
        holder.frame.setBackgroundResource(
            if (icon.id == selectedId) R.drawable.bg_icon_selected else 0
        )
        holder.frame.setOnClickListener { onPick(icon.id) }
    }
}
