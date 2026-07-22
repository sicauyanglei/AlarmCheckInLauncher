package com.example.alarmcheckinlauncher

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.alarmcheckinlauncher.databinding.ActivityGithubSettingsBinding

/**
 * GitHub 上传配置：只需填写 Token。
 *
 * - owner 由 App 用 Token 调 /user 自动获取并缓存
 * - repo/branch/path 固定为本项目仓库默认值
 * - Token 永久保存在应用私有目录（不进仓库、不进 APK）
 */
class GithubSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGithubSettingsBinding
    private val settings by lazy { GithubSettings.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGithubSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.title_github_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.editToken.setText(settings.token)

        binding.btnSave.setOnClickListener {
            val token = binding.editToken.text?.toString().orEmpty().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, R.string.toast_github_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            settings.token = token
            // 重新填 Token 时清掉旧 owner，触发下次上传时重新自动获取
            settings.owner = ""
            FileLogger.i("GitHub Token 已保存")
            Toast.makeText(this, R.string.toast_github_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
