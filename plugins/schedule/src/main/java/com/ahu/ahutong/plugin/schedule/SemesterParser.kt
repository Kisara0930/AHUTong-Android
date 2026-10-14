package com.ahu.ahutong.plugin.schedule
import com.ahu.ahutong.plugin.schedule.data.crawler.model.jwxt.CurrentSemester
import com.google.gson.Gson
import org.jsoup.Jsoup
internal suspend fun fetchCurrentSemester(jwxt: ScheduleApi): CurrentSemester {
    val basicInfo = jwxt.fetchCourseTableBasicInfo()
    val body = basicInfo.body()?.string()
        ?: throw IllegalStateException("Cannot load current semester page")
    val currentSemesterJson = Jsoup.parse(body)
        .select("script")
        .asSequence()
        .map { it.data() }
        .mapNotNull(::extractCurrentSemesterJson)
        .firstOrNull()
        ?: throw IllegalStateException("Cannot parse current semester")

    return Gson().fromJson(
        currentSemesterJson,
        CurrentSemester::class.java
    )
}

internal fun extractCurrentSemesterJson(script: String): String? {
    val assignment = Regex("(?:var|let|const)\\s+currentSemester\\s*=\\s*")
        .find(script)
        ?: return null
    var cursor = assignment.range.last + 1
    while (cursor < script.length && script[cursor].isWhitespace()) cursor += 1

    if (script.startsWith("JSON.parse", cursor)) {
        cursor += "JSON.parse".length
        while (cursor < script.length && script[cursor].isWhitespace()) cursor += 1
        if (cursor >= script.length || script[cursor] != '(') return null
        cursor += 1
        while (cursor < script.length && script[cursor].isWhitespace()) cursor += 1
        val parsed = parseJavaScriptString(script, cursor) ?: return null
        cursor = parsed.nextIndex
        while (cursor < script.length && script[cursor].isWhitespace()) cursor += 1
        return parsed.value.takeIf { cursor < script.length && script[cursor] == ')' }
    }

    return extractBalancedJsonObject(script, cursor)
}

private fun extractBalancedJsonObject(source: String, startIndex: Int): String? {
    if (startIndex >= source.length || source[startIndex] != '{') return null
    var depth = 0
    var quote: Char? = null
    var escaped = false
    for (index in startIndex until source.length) {
        val character = source[index]
        if (quote != null) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == quote -> quote = null
            }
            continue
        }
        when (character) {
            '\'', '"' -> quote = character
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return source.substring(startIndex, index + 1)
                if (depth < 0) return null
            }
        }
    }
    return null
}

private data class ParsedJavaScriptString(val value: String, val nextIndex: Int)

private fun parseJavaScriptString(source: String, startIndex: Int): ParsedJavaScriptString? {
    if (startIndex >= source.length || source[startIndex] !in charArrayOf('\'', '"')) return null
    val quote = source[startIndex]
    val result = StringBuilder()
    var cursor = startIndex + 1
    while (cursor < source.length) {
        val character = source[cursor++]
        if (character == quote) return ParsedJavaScriptString(result.toString(), cursor)
        if (character != '\\') {
            result.append(character)
            continue
        }
        if (cursor >= source.length) return null
        when (val escaped = source[cursor++]) {
            '\\', '\'', '"', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                if (cursor + 4 > source.length) return null
                val codePoint = source.substring(cursor, cursor + 4).toIntOrNull(16) ?: return null
                result.append(codePoint.toChar())
                cursor += 4
            }
            'x' -> {
                if (cursor + 2 > source.length) return null
                val codePoint = source.substring(cursor, cursor + 2).toIntOrNull(16) ?: return null
                result.append(codePoint.toChar())
                cursor += 2
            }
            '\n' -> Unit
            '\r' -> if (cursor < source.length && source[cursor] == '\n') cursor += 1
            else -> result.append(escaped)
        }
    }
    return null
}
