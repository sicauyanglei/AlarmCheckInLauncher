package com.example.alarmcheckinlauncher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText

/**
 * App 选择器：遍历系统中所有已安装的可启动 App，
 * 列表显示「应用图标 + 应用名 + 包名」，支持搜索过滤，点击返回所选包名。
 *
 * 返回 Intent：
 *  - EXTRA_PACKAGE  = 包名
 *  - EXTRA_LABEL    = 应用显示名
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var adapter: AppListAdapter
    private lateinit var progressBar: ProgressBar
    private var allApps: List<AppItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        supportActionBar?.title = getString(R.string.title_app_picker)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.progressBar)
        val rv = findViewById<RecyclerView>(R.id.recyclerApps)
        val search = findViewById<TextInputEditText>(R.id.editSearch)

        adapter = AppListAdapter { item ->
            val data = Intent().apply {
                putExtra(EXTRA_PACKAGE, item.packageName)
                putExtra(EXTRA_LABEL, item.label)
            }
            setResult(RESULT_OK, data)
            finish()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                val filtered = if (q.isEmpty()) allApps
                else allApps.filter {
                    it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
                adapter.submit(filtered)
            }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        loadApps()
    }

    /** 异步加载已安装 App 列表，避免阻塞 UI */
    private fun loadApps() {
        progressBar.visibility = android.view.View.VISIBLE
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val pm = packageManager
            val items = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map {
                    AppItem(
                        packageName = it.packageName,
                        label = pm.getApplicationLabel(it).toString(),
                        icon = pm.getApplicationIcon(it)
                    )
                }
                .sortedWith(compareBy({ it.label }, { it.packageName }))
            runOnUiThread {
                allApps = items
                adapter.submit(items)
                progressBar.visibility = android.view.View.GONE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    data class AppItem(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_LABEL = "extra_label"
    }
}
