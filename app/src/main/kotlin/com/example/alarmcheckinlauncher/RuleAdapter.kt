package com.example.alarmcheckinlauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 闹钟规则列表适配器：时间 + App 名/包名 + 启用开关 + 删除 + 点击编辑 */
class RuleAdapter(
    private val onToggle: (AlarmRule, Boolean) -> Unit,
    private val onDelete: (AlarmRule) -> Unit,
    private val onEdit: (AlarmRule) -> Unit
) : RecyclerView.Adapter<RuleAdapter.VH>() {

    private val items = mutableListOf<AlarmRule>()

    fun submit(list: List<AlarmRule>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false)
    ) {
        private val time: TextView = itemView.findViewById(R.id.textRuleTime)
        private val label: TextView = itemView.findViewById(R.id.textRuleAppLabel)
        private val pkg: TextView = itemView.findViewById(R.id.textRulePackage)
        private val sw: Switch = itemView.findViewById(R.id.switchRuleEnabled)
        private val del: ImageView = itemView.findViewById(R.id.btnRuleDelete)

        fun bind(rule: AlarmRule) {
            time.text = rule.time
            label.text = rule.appLabel
            pkg.text = rule.targetPackage
            // setOnCheckedChangeListener 会回调，先移除监听再设值避免误触发
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = rule.enabled
            sw.setOnCheckedChangeListener { _, on -> onToggle(rule, on) }
            del.setOnClickListener { onDelete(rule) }
            itemView.setOnClickListener { onEdit(rule) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
}
