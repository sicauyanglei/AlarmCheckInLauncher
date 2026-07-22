package com.example.alarmcheckinlauncher

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

        // 启动前台保活服务
        GuardService.start(this)

        bindViews()
        refreshListenerStatus()

        // 启动时统一检查并申请所有运行所需权限
        ensureStartupPermissions()
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

        // 从已安装 App 列表里选择默认 App（点击即填入包名）
        binding.btnPickApp.setOnClickListener {
            startActivityForResult(
                Intent(this, AppPickerActivity::class.java),
                REQ_PICK_DEFAULT_APP
            )
        }

        // 管理闹钟规则（每个时间点对应一个 App）
        binding.btnManageRules.setOnClickListener {
            startActivity(Intent(this, RuleListActivity::class.java))
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

        // GitHub 上传设置
        binding.btnGithubSettings.setOnClickListener {
            startActivity(Intent(this, GithubSettingsActivity::class.java))
        }

        // 上传日志到 GitHub
        binding.btnUploadLogGithub.setOnClickListener { uploadLogToGithub() }

        // 加入电池优化白名单（提升保活成功率，国产rom必须）
        binding.btnBatteryOpt.setOnClickListener { requestIgnoreBatteryOptimizations() }
    }

    /**
     * 启动时统一检查并申请所有运行所需权限，链式按顺序申请：
     *  1. POST_NOTIFICATIONS（Android 13+，运行时权限，系统弹窗）
     *  2. 电池优化白名单（系统弹窗，离开 App 后会自动返回）
     *  3. 通知使用权（系统限制无法直接申请，跳转设置页引导用户手动开启）
     *
     * 说明：
     *  - 普通权限（INTERNET、QUERY_ALL_PACKAGES、RECEIVE_BOOT_COMPLETED 等）在安装时已自动授予，无需运行时申请。
     *  - 已授予权限会自动跳过，仅在缺失时引导用户。
     *  - 首次引导会展示说明弹窗；后续启动如无缺失不再打扰。
     */
    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            FileLogger.i("启动权限: POST_NOTIFICATIONS granted=$granted")
            proceedStartupPermissionChain(fromStep = STEP_POST_NOTIFICATIONS_DONE)
        }

    private fun ensureStartupPermissions() {
        // 已授予全部所需权限则跳过
        val missing = collectMissingPermissionItems()
        if (missing.isEmpty()) {
            prefs.startupPermissionPrompted = true
            return
        }
        // 首次启动展示说明弹窗；后续启动如仍有缺失则直接进入链式申请，避免重复打扰
        if (!prefs.startupPermissionPrompted) {
            AlertDialog.Builder(this)
                .setTitle(R.string.title_startup_permission)
                .setMessage(
                    getString(
                        R.string.msg_startup_permission,
                        missing.joinToString("\n") { getString(it) }
                    )
                )
                .setPositiveButton(R.string.btn_start_grant) { _, _ ->
                    proceedStartupPermissionChain(STEP_INIT)
                }
                .setNegativeButton(R.string.btn_later) { _, _ ->
                    prefs.startupPermissionPrompted = true
                }
                .setOnCancelListener { prefs.startupPermissionPrompted = true }
                .show()
        } else {
            // 静默继续链式申请
            proceedStartupPermissionChain(STEP_INIT)
        }
    }

    private fun proceedStartupPermissionChain(fromStep: Int) {
        // Step 1: POST_NOTIFICATIONS（Android 13+）
        if (fromStep <= STEP_POST_NOTIFICATIONS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            FileLogger.i("启动权限: 请求 POST_NOTIFICATIONS")
            requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        // Step 2: 电池优化白名单
        if (fromStep <= STEP_BATTERY_OPT && !isBatteryOptimizationIgnored()) {
            FileLogger.i("启动权限: 请求加入电池优化白名单")
            prefs.startupPermissionPrompted = true
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                FileLogger.w("启动权限: 电池优化申请失败", e)
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    toast(R.string.toast_battery_manual)
                }
            }
            // 用户返回后 onResume 不会自动继续链，因此不在此处继续 step3，避免一次性跳多个设置页
            return
        }

        // Step 3: 通知使用权（无法直接申请，跳转设置页引导）
        if (fromStep <= STEP_NOTIFICATION_LISTENER && !isNotificationListenerEnabled()) {
            FileLogger.i("启动权限: 引导开启通知使用权")
            prefs.startupPermissionPrompted = true
            AlertDialog.Builder(this)
                .setTitle(R.string.title_startup_permission)
                .setMessage(getString(R.string.perm_item_notification_listener))
                .setPositiveButton(R.string.btn_grant_permission) { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton(R.string.btn_later, null)
                .show()
            return
        }

        // 全部就绪
        prefs.startupPermissionPrompted = true
        if (collectMissingPermissionItems().isEmpty()) {
            toast(R.string.toast_startup_permission_done)
        } else {
            toast(R.string.toast_startup_permission_missing)
        }
    }

    private fun collectMissingPermissionItems(): List<Int> {
        val list = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            list.add(R.string.perm_item_notifications)
        }
        if (!isBatteryOptimizationIgnored()) {
            list.add(R.string.perm_item_battery)
        }
        if (!isNotificationListenerEnabled()) {
            list.add(R.string.perm_item_notification_listener)
        }
        return list
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** 跳转到系统「电池优化」设置，引导用户把本 App 设为不受限制 */
    private fun requestIgnoreBatteryOptimizations() {
        if (isBatteryOptimizationIgnored()) {
            toast(R.string.toast_battery_already)
            return
        }
        // 优先请求系统弹窗直接加入白名单（部分厂商支持）
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // 回退：跳转电池优化列表页
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast(R.string.toast_battery_manual)
            }
        }
    }

    /** 上传日志到 GitHub 仓库（后台线程执行，避免 NetworkOnMainThread） */
    private fun uploadLogToGithub() {
        val settings = GithubSettings.get(this)
        if (settings.token.isEmpty()) {
            toast(R.string.toast_github_not_configured)
            startActivity(Intent(this, GithubSettingsActivity::class.java))
            return
        }
        val log = FileLogger.read()
        if (log.isBlank() || log.startsWith("(")) {
            toast(R.string.toast_log_empty)
            return
        }
        toast(R.string.toast_uploading)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            try {
                val url = GithubClient.uploadLogFile(settings, log)
                runOnUiThread {
                    FileLogger.i("日志已上传到 GitHub: $url")
                    AlertDialog.Builder(this)
                        .setTitle(R.string.title_upload_success)
                        .setMessage(url)
                        .setPositiveButton(R.string.btn_copy_url) { _, _ ->
                            val cm = getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("github_log_url", url))
                            toast(R.string.toast_url_copied)
                        }
                        .setNegativeButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                FileLogger.e("日志上传 GitHub 失败", e)
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.title_upload_failed)
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Deprecated("使用 registerForActivityResult 也可，这里保持简单")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_DEFAULT_APP && resultCode == RESULT_OK) {
            val pkg = data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE) ?: return
            val label = data?.getStringExtra(AppPickerActivity.EXTRA_LABEL) ?: pkg
            binding.editTargetPackage.setText(pkg)
            prefs.targetPackage = pkg
            toast("已选择：$label")
        }
    }

    companion object {
        private const val REQ_PICK_DEFAULT_APP = 2001

        // 启动权限链式申请步骤常量（值越大表示越靠后，用于跳过已完成步骤）
        private const val STEP_INIT = 0
        private const val STEP_POST_NOTIFICATIONS = 0
        private const val STEP_POST_NOTIFICATIONS_DONE = 1
        private const val STEP_BATTERY_OPT = 1
        private const val STEP_NOTIFICATION_LISTENER = 2
    }
}
