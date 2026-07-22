package com.example.alarmcheckinlauncher

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * 闹钟规则管理界面：
 *  - 列出所有规则（时间 + App 名 + 包名 + 启用开关 + 删除）
 *  - 右下角 FAB 添加新规则：先选时间 → 再选 App（跳转 AppPickerActivity）
 *  - 点击列表项可编辑（重新选时间/换 App）
 *
 * 规则永久保存在 [AlarmRuleStore]。
 */
class RuleListActivity : AppCompatActivity() {

    private lateinit var adapter: RuleAdapter
    private val store by lazy { AlarmRuleStore.get(this) }

    /** 当前正在编辑的规则 id（选完 App 后回填目标），null 表示新增 */
    private var pendingRuleId: String? = null
    private var pendingTime: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_list)
        supportActionBar?.title = getString(R.string.title_rule_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val rv = findViewById<RecyclerView>(R.id.recyclerRules)
        val empty = findViewById<TextView>(R.id.textEmpty)
        val fab = findViewById<FloatingActionButton>(R.id.fabAddRule)

        adapter = RuleAdapter(
            onToggle = { rule, on -> store.setEnabled(rule.id, on); refresh() },
            onDelete = { rule -> store.delete(rule.id); refresh() },
            onEdit = { rule -> startEdit(rule.id, rule.time) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        fab.setOnClickListener { startEdit(null, null) }

        refresh(empty)
    }

    private fun startEdit(ruleId: String?, currentTime: String?) {
        pendingRuleId = ruleId
        val (h, m) = currentTime?.split(":")?.let {
            (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
        } ?: (7 to 0)
        TimePickerDialog(this, { _, hour, minute ->
            pendingTime = String.format("%02d:%02d", hour, minute)
            // 选完时间 → 跳 App 选择器
            startActivityForResult(
                Intent(this, AppPickerActivity::class.java),
                REQ_PICK_APP
            )
        }, h, m, true).show()
    }

    @Deprecated("使用新的 registerForActivityResult 也可，这里保持简单")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_APP) return
        if (resultCode != RESULT_OK) {
            // 取消选 App，放弃本次编辑
            pendingRuleId = null
            pendingTime = null
            return
        }
        val pkg = data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE) ?: return
        val label = data?.getStringExtra(AppPickerActivity.EXTRA_LABEL) ?: pkg
        val time = pendingTime ?: return
        val existing = pendingRuleId?.let { id -> store.all().firstOrNull { it.id == id } }
        val rule = AlarmRule(
            id = pendingRuleId ?: java.util.UUID.randomUUID().toString(),
            time = time,
            targetPackage = pkg,
            appLabel = label,
            enabled = existing?.enabled ?: true
        )
        store.upsert(rule)
        FileLogger.i("保存规则 time=$time pkg=$pkg label=$label id=${rule.id}")
        pendingRuleId = null
        pendingTime = null
        refresh()
    }

    private fun refresh(emptyView: TextView? = null) {
        val list = store.all().sortedBy { it.time }
        adapter.submit(list)
        val empty = emptyView ?: findViewById<TextView>(R.id.textEmpty)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val REQ_PICK_APP = 1001
    }
}
