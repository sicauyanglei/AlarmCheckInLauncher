package com.example.alarmcheckinlauncher

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 极简 GitHub REST API 客户端：把日志内容提交到指定仓库分支的文件路径。
 *
 * 实现要点：
 *  - 使用 [PUT /repos/{owner}/{repo}/contents/{path}](https://docs.github.com/rest/repos/contents)
 *  - 文件存在时先 GET 拿 sha，再带 sha 调 PUT 完成覆盖更新
 *  - 内容用 Base64 编码（GitHub Contents API 要求）
 *  - 全部在调用方线程执行（UI 调用需走后台线程），网络用 HttpsURLConnection
 *
 * @return 上传成功返回文件 html_url，失败抛出含状态码+响应的异常
 */
object GithubClient {

    private const val API_BASE = "https://api.github.com"

    /** 用 Token 调用 /user 获取登录名，并缓存到 settings。调用方需在后台线程执行。 */
    fun ensureOwner(settings: GithubSettings): String {
        if (settings.owner.isNotEmpty()) return settings.owner
        val url = URL("$API_BASE/user")
        val (code, resp) = http("GET", url, settings.token, null)
        if (code != 200) {
            throw RuntimeException("Token 校验失败 HTTP $code: $resp")
        }
        val login = JSONObject(resp).optString("login")
        if (login.isEmpty()) throw RuntimeException("Token 无法获取用户名")
        settings.owner = login
        FileLogger.i("GithubClient 自动获取 owner=$login")
        return login
    }

    /** 上传/更新文件。调用方需在后台线程执行。 */
    fun uploadLogFile(
        settings: GithubSettings,
        content: String,
        commitMessage: String = "chore: upload alarm log"
    ): String {
        require(settings.token.isNotEmpty()) { "未配置 Token" }
        // 自动获取 owner（若尚未获取）
        val owner = ensureOwner(settings)
        val repo = settings.repo
        val branch = settings.branch
        val path = settings.path
        val token = settings.token

        // 1) 查询现有文件 sha（用于覆盖更新）；不存在则 sha=null
        val sha = fetchSha(owner, repo, path, branch, token)
        FileLogger.d("GithubClient fetchSha=$sha")

        // 2) PUT 内容
        val url = URL("$API_BASE/repos/$owner/$repo/contents/${encodePath(path)}")
        val body = JSONObject().apply {
            put("message", commitMessage)
            put("branch", branch)
            put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
            if (sha != null) put("sha", sha)
        }.toString()

        val (code, resp) = http("PUT", url, token, body)
        if (code !in 200..299) {
            throw RuntimeException("上传失败 HTTP $code: $resp")
        }
        // 解析返回里的 html_url
        val htmlUrl = JSONObject(resp).optJSONObject("content")?.optString("html_url")
            ?: "https://github.com/$owner/$repo/blob/$branch/$path"
        FileLogger.i("GithubClient 上传成功 url=$htmlUrl")
        return htmlUrl
    }

    /** 获取文件 sha；不存在返回 null */
    private fun fetchSha(owner: String, repo: String, path: String, branch: String, token: String): String? {
        return try {
            val url = URL("$API_BASE/repos/$owner/$repo/contents/${encodePath(path)}?ref=$branch")
            val (code, resp) = http("GET", url, token, null)
            if (code == 200) JSONObject(resp).optString("sha").takeIf { it.isNotEmpty() } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    private fun http(
        method: String,
        url: URL,
        token: String,
        body: String?
    ): Pair<Int, String> {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "AlarmCheckInLauncher")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to resp
        } finally {
            conn.disconnect()
        }
    }
}
