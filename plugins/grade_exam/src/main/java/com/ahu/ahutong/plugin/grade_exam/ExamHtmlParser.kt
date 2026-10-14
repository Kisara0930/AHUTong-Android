package com.ahu.ahutong.plugin.grade_exam
import com.ahu.ahutong.data.model.Exam
import com.google.gson.JsonParser
import org.jsoup.Jsoup
internal object ExamHtmlParser {
    fun parseExamTableHtml(html: String): List<Exam> {
        // 1. Parse studentExamList for seat number mapping (exam id -> seat number)
        val seatMap = mutableMapOf<String, String>()
        val seatListRegex = Regex("(?s)var\\s+studentExamList\\s*=\\s*(\\[.+?\\]);")
        seatListRegex.find(html)?.let { match ->
            val jsonStr = match.groupValues[1].replace("'", "\"")
            try {
                val arr = JsonParser.parseString(jsonStr).asJsonArray
                arr.forEach {
                    val obj = it.asJsonObject
                    val id = obj.get("id")?.asString ?: obj.get("id")?.asLong?.toString() ?: ""
                    val seat = obj.get("seatNo")?.asString ?: obj.get("seatNo")?.asLong?.toString() ?: ""
                    if (id.isNotEmpty()) seatMap[id] = seat
                }
            } catch (_: Exception) { }
        }

        // 2. Parse HTML table rows
        val doc = Jsoup.parse(html)
        val rows = doc.select("tr[data-finished]")
        if (rows.isEmpty()) return emptyList()

        return rows.map { row ->
            val finished = row.attr("data-finished") == "true"

            // Time from <div class="time ...">
            val time = row.select("div.time").first()?.text()?.trim() ?: ""

            // Course name from bold <span>
            val course = row.select("span[style*=font-weight]").firstOrNull { el ->
                el.attr("style").contains("bold")
            }?.text()?.trim() ?: ""

            // Exam type from <span class="tag-span typeX">
            val examType = row.select("span.tag-span").first()?.text()?.trim() ?: ""

            // Seat exam ID from <span id="seat-NNN">
            val seatId = row.select("span[id^=seat-]").first()?.id()?.removePrefix("seat-") ?: ""
            val seatNum = seatMap[seatId] ?: ""

            // Location: campus, building, room from spans in first <td>
            val firstTd = row.select("td").first()
            val locationSpans = firstTd?.select("span")?.filter {
                !it.id().startsWith("seat-") && it.text().trim().isNotEmpty()
            } ?: emptyList()
            val location = locationSpans.joinToString("-") { it.text().trim() }

            val courseDisplay = if (examType.isNotEmpty()) "$course($examType)" else course

            Exam().apply {
                setCourse(courseDisplay)
                setTime(time)
                setSeatNum(seatNum)
                setLocation(location)
                setFinished(finished)
            }
        }
    }
}
