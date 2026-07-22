package com.example.alarmcheckinlauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.alarmcheckinlauncher.AppPickerActivity.AppItem

/** 已安装 App 列表适配器，点击触发回调 */
class AppListAdapter(
    private val onClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    private val items = mutableListOf<AppItem>()

    fun submit(list: List<AppItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
    ) {
        private val label: TextView = itemView.findViewById(R.id.textAppLabel)
        private val pkg: TextView = itemView.findViewById(R.id.textAppPackage)
        private val icon: android.widget.ImageView = itemView.findViewById(R.id.imageAppIcon)

        fun bind(item: AppItem) {
            label.text = item.label
            pkg.text = item.packageName
            icon.setImageDrawable(item.icon)
            itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
}
