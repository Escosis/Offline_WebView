package com.escosis.offlinewebview

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

data class FileBrowserItem(
    val name: String,
    val isDirectory: Boolean,
    val onOpen: () -> Unit
)

class FileBrowserAdapter(
    private var items: List<FileBrowserItem>,
    private val onItemClick: (FileBrowserItem) -> Unit
) : RecyclerView.Adapter<FileBrowserAdapter.ViewHolder>() {

    var nightMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_browser, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.nameText.text = item.name
        holder.nameText.setTextColor(if (nightMode) Color.WHITE else Color.BLACK)

        val iconRes = if (item.isDirectory) {
            R.drawable.baseline_folder_24
        } else {
            R.drawable.baseline_insert_drive_file_24
        }
        holder.icon.setImageResource(iconRes)
        val iconColor = if (nightMode) Color.WHITE else Color.DKGRAY
        holder.icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<FileBrowserItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.fileIcon)
        val nameText: TextView = itemView.findViewById(R.id.fileName)
    }
}