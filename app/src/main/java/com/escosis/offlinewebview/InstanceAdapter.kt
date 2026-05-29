// 文件路径: com/escosis/offlinewebview/InstanceAdapter.kt
package com.escosis.offlinewebview

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class InstanceAdapter(
    private var instances: List<Instance>,
    private val onItemClick: (Instance) -> Unit,
    private val onDeleteClick: (Instance) -> Unit
) : RecyclerView.Adapter<InstanceAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // 夜间模式标志，外部修改后刷新整个列表
    var isNightMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_instance, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val instance = instances[position]
        val displayName = instance.name
        holder.nameText.text = displayName
        holder.timeText.text = dateFormat.format(Date(instance.createdAt))

        // 设置文字颜色（夜间模式适配）
        val textColor = if (isNightMode) Color.WHITE else Color.BLACK
        val timeColor = if (isNightMode) Color.LTGRAY else Color.GRAY
        holder.nameText.setTextColor(textColor)
        holder.timeText.setTextColor(timeColor)

        // 设置删除按钮图标颜色（夜间模式适配）
        val iconColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        holder.deleteButton.drawable?.setColorFilter(
            PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        )

        holder.deleteButton.setOnClickListener {
            onDeleteClick(instance)
        }
        holder.itemView.setOnClickListener {
            onItemClick(instance)
        }
    }

    override fun getItemCount(): Int = instances.size

    fun updateData(newInstances: List<Instance>) {
        instances = newInstances
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(R.id.instanceNameText)
        val timeText: TextView = itemView.findViewById(R.id.instanceTimeText)
        val deleteButton: ImageButton = itemView.findViewById(R.id.deleteInstanceButton)
    }
}