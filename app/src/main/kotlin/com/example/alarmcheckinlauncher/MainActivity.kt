package com.example.alarmcheckinlauncher

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.alarmcheckinlauncher.databinding.ActivityMainBinding

/**
 * 主界面：
 *  - 配置目标打卡 App 包名
 *  - 启用/暂停监听
 *  - 跳转系统「通知使用权」授权页
 *  - 显示当前监听服务是否已生效
 *  - 测试一键拉起目标 App
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPreferences.get(this)
        FileLogger.init(this)
        FileLogger.i("MainActivity onCreate")

        bindViews()
        refreshListenerStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshListenerStatus()
    }

    private fun bindViews() {
        // 回填已保存的包名
        binding.editTargetPackage.setText(prefs.targetPackage)

        // 启用开关
        binding.switchEnabled.isChecked = prefs.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.enabled = isChecked
            toast(if (isChecked) R.string.toast_enabled else R.string.toast_disabled)
        }

        // 保存包名
        binding.btnSavePackage.setOnClickListener {
            val pkg = binding.editTargetPackage.text?.toString().orEmpty().trim()
            if (pkg.isEmpty()) {
                toast(R.string.toast_pkg_empty)
                return@setOnClickListener
            }
            prefs.targetPackage = pkg
            toast(R.string.toast_pkg_saved)
        }

        // 一键填入常见打卡 App 包名
        binding.btnPresetDingTalk.setOnClickListener {
            binding.editTargetPackage.setText("com.alibaba.android.rimet")
        }
        binding.btnPresetWeWork.setOnClickListener {
            binding.editTargetPackage.setText("com.tencent.wework")
        }
        binding.btnPresetFeishu.setOnClickListener {
            binding.editTargetPackage.setText("com.ss.android.lark")
        }

        // 跳转通知使用权授权
        binding.btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        // 测试拉起目标 App
        binding.btnTestLaunch.setOnClickListener {
            val pkg = binding.editTargetPackage.text?.toString().orEmpty().trim()
            if (pkg.isEmpty()) {
                toast(R.string.toast_pkg_empty)
                return@setOnClickListener
            }
            prefs.targetPackage = pkg
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent == null) {
                toast(R.string.toast_target_not_installed)
                return@setOnClickListener
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }

        // 查看日志（弹窗显示，便于排查「闹钟响了没拉起」）
        binding.btnViewLog.setOnClickListener {
            FileLogger.i("用户查看日志")
            AlertDialog.Builder(this)
                .setTitle(R.string.title_log)
                .setMessage(FileLogger.read())
                .setPositiveButton(R.string.btn_share_log) { _, _ -> shareLog() }
                .setNegativeButton(R.string.btn_clear_log) { _, _ ->
                    FileLogger.clear()
                    toast(R.string.toast_log_cleared)
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        }

        // 分享日志（导出给开发者排查）
        binding.btnShareLog.setOnClickListener { shareLog() }

        // 清空日志
        binding.btnClearLog.setOnClickListener {
            FileLogger.clear()
            toast(R.string.toast_log_cleared)
        }
    }

    private fun shareLog() {
        val log = FileLogger.read()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "提醒打卡 - 运行日志")
            putExtra(Intent.EXTRA_TEXT, log)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.btn_share_log)))
    }

    /** 检查本应用的 NotificationListenerService 是否已获授权 */
    private fun refreshListenerStatus() {
        val enabled = isNotificationListenerEnabled()
        binding.textStatus.text = getString(
            if (enabled) R.string.status_listener_on else R.string.status_listener_off
        )
        binding.btnGrantPermission.isEnabled = !enabled
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val target = ComponentName(this, AlarmNotificationListener::class.java).flattenToString()
        return !TextUtils.isEmpty(flat) && flat.split(":").any { it == target }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
