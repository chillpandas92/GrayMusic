package com.example.localmusicapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 调用 DeepSeek 的 OpenAI 兼容 Chat Completions 接口，对歌曲生成约 200 字的中文锐评。
 *
 * 仅使用 JDK 自带的 HttpURLConnection，不引入第三方 HTTP 库，避免体积膨胀。
 */
object AiCritiqueClient {

    private const val ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
    private const val MODEL = "deepseek-chat"

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val message: String) : Result()
    }

    /**
     * @param apiKey DeepSeek 控制台里生成的 sk-...
     * @param title 歌曲名
     * @param artist 歌手（可能为多位，已格式化成字符串）
     * @param album 专辑
     * @param lyricsSnippet 歌词片段（前 ~1500 字符就够了；太长的歌只取开头）
     */
    suspend fun generateCritique(
        apiKey: String,
        title: String,
        artist: String,
        album: String,
        lyricsSnippet: String
    ): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Err("请先在设置中填入 DeepSeek API Key")

        val songName = title.ifBlank { "未知" }
        // 用户指定的指令模板：
        //   "你能否锐评《歌名》这首歌？用200字。解析歌词。"
        // 歌词文本附在后面作为参考，没歌词也允许模型凭对歌的公共认知写
        val userContent = buildString {
            append("你能否锐评《").append(songName).append("》这首歌？用200字。解析歌词。")
            if (artist.isNotBlank()) append("\n歌手：").append(artist)
            if (album.isNotBlank()) append("\n专辑：").append(album)
            if (lyricsSnippet.isNotBlank()) {
                append("\n\n歌词：\n").append(lyricsSnippet.take(1500))
            }
        }

        val bodyJson = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.8)
            // 200 汉字大约 300 个 token；给 800 足够的余量，避免 DeepSeek 端被 finish_reason=length 截断。
            put("max_tokens", 800)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            val url = URL(ENDPOINT)
            conn = (url.openConnection() as HttpURLConnection).apply {
                if (this is HttpsURLConnection) {
                    // 走默认 SSL，无需自定义 TrustManager
                }
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                return@withContext Result.Err(extractErrorMessage(respText, code))
            }

            val json = JSONObject(respText)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext Result.Err("接口返回为空")
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
            val raw = message?.optString("content").orEmpty().trim()
            if (raw.isEmpty()) return@withContext Result.Err("接口未返回文本")

            Result.Ok(cleanupCritiqueText(raw))
        } catch (e: Exception) {
            Result.Err("请求失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun extractErrorMessage(body: String, httpCode: Int): String {
        if (body.isBlank()) return "HTTP $httpCode"
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error")
            err?.optString("message")?.takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: "HTTP $httpCode"
        } catch (_: Exception) {
            "HTTP $httpCode：$body"
        }
    }

    /**
     * 只做轻量清理：压缩多余空行、去首尾空白、剥掉常见的包裹引号和 "锐评："/"评：" 前缀。
     * 不再做字数硬截断 —— "用200字" 已经在 prompt 里告诉模型，这里放行完整输出，
     * 即使模型稍微超出也让用户看到完整的锐评，而不是把尾巴切掉再补省略号。
     */
    private fun cleanupCritiqueText(text: String): String {
        return text
            .replace("\n\n+".toRegex(), "\n")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removeSurrounding("“", "”")
            .removeSurrounding("‘", "’")
            .removePrefix("锐评：")
            .removePrefix("锐评:")
            .removePrefix("评：")
            .removePrefix("评:")
            .trim()
    }
}
