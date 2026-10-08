package com.example.ui.list

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.R
import com.example.data.NoteImageCache

/**
 * 对齐官方 [com.miui.notes.basefeature.notelist.BaseNoteListAdapter]：
 * VIEW_TYPE_LIST / VIEW_TYPE_GRID → CommonNoteListItem / CommonNoteGridItem
 */
class NoteListAdapter(
    private val onClick: (NoteListItem) -> Unit,
    private val onLongClick: (NoteListItem) -> Unit
) : ListAdapter<NoteListItem, NoteListAdapter.NoteVH>(DIFF) {

    var gridMode: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun getItemViewType(position: Int): Int =
        if (gridMode) VIEW_TYPE_GRID else VIEW_TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteVH {
        val layout = if (viewType == VIEW_TYPE_GRID) {
            R.layout.item_note_grid
        } else {
            R.layout.item_note_list
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return NoteVH(view)
    }

    override fun onBindViewHolder(holder: NoteVH, position: Int) {
        holder.bind(getItem(position), gridMode, onClick, onLongClick)
    }

    override fun onViewRecycled(holder: NoteVH) {
        holder.clearImage()
        super.onViewRecycled(holder)
    }

    class NoteVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: View = itemView.findViewById(R.id.note_container)
        private val title: TextView = itemView.findViewById(R.id.note_title)
        private val snippet: TextView = itemView.findViewById(R.id.note_snippet)
        private val time: TextView = itemView.findViewById(R.id.note_time)
        private val stick: ImageView = itemView.findViewById(R.id.note_stick)
        private val image: ImageView = itemView.findViewById(R.id.note_image)

        fun bind(
            item: NoteListItem,
            gridMode: Boolean,
            onClick: (NoteListItem) -> Unit,
            onLongClick: (NoteListItem) -> Unit
        ) {
            title.text = item.displayTitle
            snippet.text = item.bodyText
            time.text = item.formattedDate
            stick.isVisible = item.sticky

            val radius = itemView.resources.getDimension(R.dimen.common_note_list_item_bg_radius)
            val bg = GradientDrawable().apply {
                cornerRadius = radius
                setColor(item.bgColor)
            }
            container.background = bg

            val fileId = item.firstImageFileId
            if (fileId.isNullOrBlank()) {
                image.isVisible = false
                image.setImageDrawable(null)
            } else {
                val file = NoteImageCache.findCachedFile(itemView.context, fileId)
                if (file == null) {
                    image.isVisible = false
                    image.setImageDrawable(null)
                } else {
                    image.isVisible = true
                    // 图片变换圆角必须与 ImageView 背景圆角一致，否则露底差一截
                    val thumbRadius = if (gridMode) {
                        itemView.resources.getDimension(R.dimen.common_note_list_item_bg_radius)
                    } else {
                        itemView.resources.getDimension(R.dimen.list_item_preview_image_radius)
                    }
                    image.background = GradientDrawable().apply {
                        setColor(0xFFE8EAED.toInt())
                        if (gridMode) {
                            // 网格顶图：上圆角贴合卡片，下沿贴正文
                            cornerRadii = floatArrayOf(
                                thumbRadius, thumbRadius,
                                thumbRadius, thumbRadius,
                                0f, 0f,
                                0f, 0f
                            )
                        } else {
                            cornerRadius = thumbRadius
                        }
                    }
                    image.load(file) {
                        crossfade(false)
                        if (gridMode) {
                            transformations(
                                RoundedCornersTransformation(
                                    thumbRadius, thumbRadius, 0f, 0f
                                )
                            )
                        } else {
                            transformations(RoundedCornersTransformation(thumbRadius))
                        }
                    }
                }
            }

            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        fun clearImage() {
            image.setImageDrawable(null)
        }
    }

    companion object {
        /** 与官方 BaseNoteListAdapter VIEW_TYPE_LIST / VIEW_TYPE_GRID 一致 */
        const val VIEW_TYPE_LIST = 4
        const val VIEW_TYPE_GRID = 5

        private val DIFF = object : DiffUtil.ItemCallback<NoteListItem>() {
            override fun areItemsTheSame(oldItem: NoteListItem, newItem: NoteListItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NoteListItem, newItem: NoteListItem): Boolean =
                oldItem == newItem
        }
    }
}
