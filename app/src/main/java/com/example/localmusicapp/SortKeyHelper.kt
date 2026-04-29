package com.example.localmusicapp

import com.github.promeg.pinyinhelper.Pinyin
import java.util.concurrent.ConcurrentHashMap

/**
 * 中文 / 英文统一排序与搜索 key。
 *
 * - 中文转拼音
 * - 英文数字保留原字符并转小写
 * - 做缓存，避免列表排序 / 搜索时重复计算
 */
object SortKeyHelper {

    private val cache = ConcurrentHashMap<String, String>()

    /**
     * 少量多音字 / 词语定制：只在用户明确提出的标题上做精确覆盖，
     * 避免把所有“长”字都强制改成同一读音。
     */
    private val phraseOverrides = linkedMapOf(
        "长城" to "changcheng"
    )

    fun keyOf(text: String): String {
        if (text.isEmpty()) return ""
        cache[text]?.let { return it }

        var index = 0
        val built = buildString(text.length * 2) {
            while (index < text.length) {
                val override = phraseOverrides.entries.firstOrNull { (phrase, _) ->
                    text.regionMatches(index, phrase, 0, phrase.length)
                }
                if (override != null) {
                    append(override.value)
                    append(' ')
                    index += override.key.length
                    continue
                }

                val c = text[index]
                if (Pinyin.isChinese(c)) {
                    append(Pinyin.toPinyin(c).lowercase())
                    append(' ')
                } else {
                    append(c.lowercaseChar())
                }
                index++
            }
        }

        cache[text] = built
        return built
    }

    fun searchKeyOf(text: String): String = keyOf(text).replace(" ", "")
}
