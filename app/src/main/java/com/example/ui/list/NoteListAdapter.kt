package com.example.ui.list

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
 * 支持长按多选。
 */
class NoteListAdapter(
    private val onOpen: (NoteListItem) -> Unit,
    private val onSelectionChanged: (selectionMode: Boolean, selectedCount: Int) -> Unit
) : ListAdapter<NoteListItem, NoteListAdapter.NoteVH>(DIFF) {

    var gridMode: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var selectionMode: Boolean = false
        private set

    private val selectedIds = linkedSetOf<String>()

    init {
        setHasStableIds(true)
    }

    fun selectedIds(): Set<String> = selectedIds.toSet()

    fun selectedCount(): Int = selectedIds.size

    /** 仅选中一条时返回该项，否则 null */
    fun singleSelectedItem(): NoteListItem? {
        if (selectedIds.size != 1) return null
        val id = selectedIds.first()
        return currentList.find { it.id == id }
    }

    fun enterSelection(initialId: String) {
        if (!selectionMode) {
            selectionMode = true
            selectedIds.clear()
        }
        selectedIds.add(initialId)
        notifyDataSetChanged()
        emitSelection()
    }

    fun exitSelection() {
        if (!selectionMode && selectedIds.isEmpty()) return
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        emitSelection()
    }

    fun toggleSelection(id: String) {
        if (!selectionMode) return
        if (!selectedIds.add(id)) selectedIds.remove(id)
        if (selectedIds.isEmpty()) {
            exitSelection()
            return
        }
        val index = currentList.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_SELECTION)
        else notifyDataSetChanged()
        emitSelection()
    }

    fun selectAllVisible() {
        if (!selectionMode) selectionMode = true
        selectedIds.clear()
        currentList.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
        emitSelection()
    }

    fun isAllVisibleSelected(): Boolean =
        currentList.isNotEmpty() && currentList.all { selectedIds.contains(it.id) }

    private fun emitSelection() {
        onSelectionChanged(selectionMode, selectedIds.size)
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
        holder.bind(getItem(position), gridMode, selectionMode, selectedIds.contains(getItem(position).id))
    }

    override fun onBindViewHolder(holder: NoteVH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(
                selectionMode,
                selectedIds.contains(getItem(position).id)
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: NoteVH) {
        holder.clearImage()
        super.onViewRecycled(holder)
    }

    inner class NoteVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: View = itemView.findViewById(R.id.note_container)
        private val title: TextView = itemView.findViewById(R.id.note_title)
        private val snippet: TextView = itemView.findViewById(R.id.note_snippet)
        private val time: TextView = itemView.findViewById(R.id.note_time)
        private val stick: ImageView = itemView.findViewById(R.id.note_stick)
        private val image: ImageView = itemView.findViewById(R.id.note_image)
        private val check: FrameLayout = itemView.findViewById(R.id.note_check)
        private val checkIcon: ImageView = itemView.findViewById(R.id.note_check_icon)

        fun bind(
            item: NoteListItem,
            gridMode: Boolean,
            selectionMode: Boolean,
            selected: Boolean
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
                    val thumbRadius = if (gridMode) {
                        itemView.resources.getDimension(R.dimen.common_note_list_item_bg_radius)
                    } else {
                        itemView.resources.getDimension(R.dimen.list_item_preview_image_radius)
                    }
                    image.background = GradientDrawable().apply {
                        setColor(0xFFE8EAED.toInt())
                        if (gridMode) {
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

            bindSelection(selectionMode, selected)

            itemView.setOnClickListener {
                if (this@NoteListAdapter.selectionMode) {
                    toggleSelection(item.id)
                } else {
                    onOpen(item)
                }
            }
            itemView.setOnLongClickListener {
                if (this@NoteListAdapter.selectionMode) {
                    toggleSelection(item.id)
                } else {
                    enterSelection(item.id)
                }
                true
            }
        }

        fun bindSelection(selectionMode: Boolean, selected: Boolean) {
            check.isVisible = selectionMode
            check.isSelected = selected
            checkIcon.isVisible = selected
            itemView.alpha = if (selectionMode && !selected) 0.92f else 1f
        }

        fun clearImage() {
            image.setImageDrawable(null)
        }
    }

    companion object {
        const val VIEW_TYPE_LIST = 4
        const val VIEW_TYPE_GRID = 5
        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<NoteListItem>() {
            override fun areItemsTheSame(oldItem: NoteListItem, newItem: NoteListItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NoteListItem, newItem: NoteListItem): Boolean =
                oldItem == newItem
        }
    }
}
