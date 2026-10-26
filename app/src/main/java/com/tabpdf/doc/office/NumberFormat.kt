package com.tabpdf.doc.office

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** A cell's value as shown, and the colour its format asks for (`[Red]`), if any. */
class Formatted(val text: String, val color: Int?)

/**
 * Excel number formats (기획서 5.7: xlsx): the built-in ones and format codes — sections for
 * positive, negative, zero and text; digits, thousands separators, decimals, percent, scientific;
 * dates and times; colours, conditions, literal text. Fractions show as decimals. Dates are shown
 * as the Korean edition of Excel shows its built-in date formats.
 */
object NumberFormat {
    /** Built-in formats by id, as the Korean Excel shows them. */
    private val BUILT_IN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        5 to "\"₩\"#,##0;\\-\"₩\"#,##0", 6 to "\"₩\"#,##0;[Red]\\-\"₩\"#,##0",
        7 to "\"₩\"#,##0.00;\\-\"₩\"#,##0.00", 8 to "\"₩\"#,##0.00;[Red]\\-\"₩\"#,##0.00",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "yyyy-mm-dd", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "yyyy-mm-dd h:mm",
        27 to "yyyy\"년\" m\"월\"", 28 to "m\"월\" d\"일\"", 29 to "m\"월\" d\"일\"", 30 to "mm-dd-yy",
        31 to "yyyy\"년\" m\"월\" d\"일\"", 32 to "h\"시\" mm\"분\"", 33 to "h\"시\" mm\"분\" ss\"초\"",
        34 to "yyyy-mm-dd", 35 to "yyyy-mm-dd", 36 to "yyyy\"년\" m\"월\"",
        37 to "#,##0;-#,##0", 38 to "#,##0;[Red]-#,##0", 39 to "#,##0.00;-#,##0.00", 40 to "#,##0.00;[Red]-#,##0.00",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
        50 to "yyyy\"년\" m\"월\"", 51 to "m\"월\" d\"일\"", 52 to "yyyy-mm-dd", 53 to "yyyy-mm-dd",
        54 to "m\"월\" d\"일\"", 55 to "yyyy-mm-dd", 56 to "yyyy-mm-dd", 57 to "yyyy\"년\" m\"월\"", 58 to "m\"월\" d\"일\"",
    )

    fun builtIn(id: Int): String = BUILT_IN[id] ?: "General"

    /**
     * Excel's own limits: format codes of at most 255 characters, at most 30 decimals. Longer ones
     * come from crafted files and only serve to blow one cell up to megabytes of text.
     */
    private const val MAX_CODE_LENGTH = 255
    private const val MAX_DECIMALS = 30

    /** Whether [code] shows numbers as dates or times. */
    fun isDate(code: String): Boolean = code.length <= MAX_CODE_LENGTH && (sections(code).firstOrNull()?.let { dateTokens(it) } ?: false)

    fun format(value: Double, code: String, date1904: Boolean): Formatted {
        // Excel never stores these (a cell with an error says so); rounding them would throw.
        if (!value.isFinite()) return Formatted("#NUM!", null)
        if (code.length > MAX_CODE_LENGTH) return Formatted(general(value), null)
        val all = sections(code)
        if (all.isEmpty() || code.equals("General", true)) return Formatted(general(value), null)
        // Conditions ([>=100]) pick a section; otherwise positive;negative;zero.
        val conditional = all.indexOfFirst { CONDITION.containsMatchIn(it) }
        val (section, negativeShown) = if (conditional >= 0) {
            val chosen = all.firstOrNull { s -> CONDITION.find(s)?.let { test(it, value) } ?: false }
                ?: all.firstOrNull { !CONDITION.containsMatchIn(it) && it != "@" } ?: all.last()
            chosen to false
        } else when {
            value < 0 && all.size >= 2 -> all[1] to true
            value == 0.0 && all.size >= 3 -> all[2] to false
            else -> all[0] to false
        }
        val color = COLOR.find(section)?.groupValues?.get(1)?.let { colorOf(it) }
        val clean = section.replace(COLOR, "").replace(CONDITION, "")
        val v = if (negativeShown) abs(value) else value
        val text = when {
            clean.equals("General", true) -> general(v)
            dateTokens(clean) -> date(v, clean, date1904) ?: general(v)
            else -> number(v, clean)
        }
        return Formatted(text, color)
    }

    /** Text in a cell with format [code]: its text section, with @ for the text. */
    fun formatText(text: String, code: String): Formatted {
        if (code.length > MAX_CODE_LENGTH) return Formatted(text, null)
        val all = sections(code)
        val section = when {
            all.size >= 4 -> all[3]
            all.any { it.contains('@') } -> all.first { it.contains('@') }
            else -> return Formatted(text, null)
        }
        val color = COLOR.find(section)?.groupValues?.get(1)?.let { colorOf(it) }
        return Formatted(literal(section.replace(COLOR, "")) { if (it == '@') text else null }, color)
    }

    /** Excel's General: up to 11 characters of number, scientific when it must. */
    fun general(value: Double): String {
        if (value == 0.0) return "0"
        if (value.isNaN() || value.isInfinite()) return "#NUM!"
        val a = abs(value)
        if (a >= 1e11 || a < 1e-9) {
            val exponent = floor(log10(a)).toInt()
            val mantissa = value / 10.0.pow(exponent)
            val m = trimZeros("%.5f".format(java.util.Locale.ROOT, mantissa))
            return m + "E" + (if (exponent < 0) "-" else "+") + "%02d".format(abs(exponent))
        }
        val digitsBefore = if (a >= 1) floor(log10(a)).toInt() + 1 else 1
        val decimals = (10 - digitsBefore).coerceIn(0, 10)
        return trimZeros("%.${decimals}f".format(java.util.Locale.ROOT, value))
    }

    private fun trimZeros(s: String): String = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s

    /** [code] split into sections at semicolons outside quotes and brackets. */
    private fun sections(code: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote = false
        var bracket = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' && i + 1 < code.length -> { current.append(c).append(code[i + 1]); i++ }
                c == '"' -> { quote = !quote; current.append(c) }
                !quote && c == '[' -> { bracket = true; current.append(c) }
                !quote && c == ']' -> { bracket = false; current.append(c) }
                !quote && !bracket && c == ';' -> { result += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result += current.toString()
        return result
    }

    private val COLOR = Regex("""\[(Black|Blue|Cyan|Green|Magenta|Red|White|Yellow|Color\s*\d+|검정|파랑|녹청|녹색|자홍|빨강|흰색|노랑)]""", RegexOption.IGNORE_CASE)
    private val CONDITION = Regex("""\[(<=|>=|<>|<|>|=)\s*(-?[\d.]+)]""")

    private fun test(m: MatchResult, v: Double): Boolean {
        val n = m.groupValues[2].toDoubleOrNull() ?: return false
        return when (m.groupValues[1]) {
            "<" -> v < n; "<=" -> v <= n; ">" -> v > n; ">=" -> v >= n; "=" -> v == n; "<>" -> v != n
            else -> false
        }
    }

    private fun colorOf(name: String): Int? = when (name.lowercase()) {
        "black", "검정" -> 0xFF000000.toInt()
        "blue", "파랑" -> 0xFF0000FF.toInt()
        "cyan", "녹청" -> 0xFF00FFFF.toInt()
        "green", "녹색" -> 0xFF00FF00.toInt()
        "magenta", "자홍" -> 0xFFFF00FF.toInt()
        "red", "빨강" -> 0xFFFF0000.toInt()
        "white", "흰색" -> 0xFFFFFFFF.toInt()
        "yellow", "노랑" -> 0xFFFFFF00.toInt()
        else -> null
    }

    /** Whether a section has date or time codes outside quoted text and brackets (but [h] counts). */
    private fun dateTokens(section: String): Boolean {
        var quote = false
        var i = 0
        while (i < section.length) {
            val c = section[i]
            when {
                c == '\\' -> i++
                c == '"' -> quote = !quote
                !quote && c == '[' -> {
                    val end = section.indexOf(']', i)
                    val inside = if (end > i) section.substring(i + 1, end).lowercase() else ""
                    if (inside.isNotEmpty() && inside.all { it == 'h' || it == 'm' || it == 's' }) return true
                    i = if (end > i) end else i
                }
                !quote && c.lowercaseChar() in "ydhs" -> return true
                !quote && c.lowercaseChar() == 'm' && !section.contains('0') && !section.contains('#') -> return true
                !quote && (c == '0' || c == '#') -> return false
            }
            i++
        }
        return false
    }

    /** Text of a section with its literal parts: quoted text, escaped characters, `_x` spaces, `*x` fills dropped. */
    private inline fun literal(section: String, placeholder: (Char) -> String?): String {
        val out = StringBuilder()
        var i = 0
        while (i < section.length) {
            val c = section[i]
            when {
                c == '"' -> {
                    val end = section.indexOf('"', i + 1).let { if (it < 0) section.length else it }
                    out.append(section, i + 1, end)
                    i = end
                }
                c == '\\' && i + 1 < section.length -> { out.append(section[i + 1]); i++ }
                c == '_' && i + 1 < section.length -> { out.append(' '); i++ }
                c == '*' && i + 1 < section.length -> i++
                c == '[' -> {
                    val end = section.indexOf(']', i)
                    // [$₩-412]: a currency symbol and locale; the symbol is shown.
                    if (end > i && section[i + 1] == '$') out.append(section.substring(i + 2, end).substringBefore('-'))
                    i = if (end > i) end else i
                }
                else -> out.append(placeholder(c) ?: c.toString())
            }
            i++
        }
        return out.toString()
    }

    private fun number(value: Double, section: String): String {
        // The part with digit placeholders, and the literal text before and after it.
        var v = value
        val plain = StringBuilder()
        run {
            var quote = false
            for (c in section) {
                if (c == '"') quote = !quote
                if (!quote) plain.append(c) else plain.append(' ')
            }
        }
        val p = plain.toString()
        if (p.contains('%')) v *= 100.0.pow(p.count { it == '%' })
        // Enough percent signs take any number past Double's range (and zero to NaN, which can't be rounded).
        if (!v.isFinite()) return "#NUM!"
        val firstDigit = p.indexOfFirst { it == '0' || it == '#' || it == '?' }
        if (firstDigit < 0) return literal(section) { if (it == '@') general(value) else null }
        var lastDigit = p.indexOfLast { it == '0' || it == '#' || it == '?' }
        // Scientific: 0.00E+00
        val e = p.indexOf("E+", ignoreCase = true).takeIf { it in firstDigit..lastDigit } ?: p.indexOf("E-", ignoreCase = true).takeIf { it in firstDigit..lastDigit }
        val pattern = section.substring(firstDigit, lastDigit + 1)
        val before = literal(section.substring(0, firstDigit)) { null }
        var afterStart = lastDigit + 1
        // Commas right after the digits scale (see below), they aren't text.
        if (e == null) while (afterStart < p.length && p[afterStart] == ',') afterStart++
        val after = literal(section.substring(afterStart)) { null }
        if (e != null) {
            val mantissaPattern = section.substring(firstDigit, e)
            val expDigits = section.substring(e + 2, lastDigit + 1).count { it == '0' }.coerceAtLeast(1)
            val decimals = mantissaPattern.substringAfter('.', "").count { it == '0' || it == '#' }.coerceAtMost(MAX_DECIMALS)
            val intDigits = mantissaPattern.substringBefore('.').count { it == '0' || it == '#' }.coerceAtLeast(1)
            if (v == 0.0) return before + "0" + (if (decimals > 0) "." + "0".repeat(decimals) else "") + "E+" + "0".repeat(expDigits) + after
            var exponent = floor(log10(abs(v))).toInt()
            // ##0.0E+0: the exponent a multiple of the integer digits (engineering notation).
            if (intDigits > 1 && mantissaPattern.startsWith("#")) exponent = Math.floorDiv(exponent, intDigits) * intDigits
            val mantissa = v / 10.0.pow(exponent)
            val m = "%.${decimals}f".format(java.util.Locale.ROOT, abs(mantissa))
            return before + (if (v < 0) "-" else "") + m + "E" + (if (exponent < 0) "-" else "+") +
                abs(exponent).toString().padStart(expDigits, '0') + after
        }
        // Commas after the last digit divide by a thousand each.
        var scaled = v
        var k = lastDigit + 1
        while (k < p.length && p[k] == ',') { scaled /= 1000.0; k++ }
        val point = pattern.indexOf('.')
        val intPattern = if (point >= 0) pattern.substring(0, point) else pattern
        val decPattern = if (point >= 0) pattern.substring(point + 1) else ""
        val decimals = decPattern.count { it == '0' || it == '#' || it == '?' }.coerceAtMost(MAX_DECIMALS)
        val minDecimals = decPattern.count { it == '0' }.coerceAtMost(decimals)
        val grouping = intPattern.contains(',')
        val minInt = intPattern.count { it == '0' }
        val factor = 10.0.pow(decimals)
        val rounded = (abs(scaled) * factor).roundToLong()
        var intPart = (rounded / factor.toLong().coerceAtLeast(1)).toString()
        var decPart = if (decimals > 0) (rounded % factor.toLong()).toString().padStart(decimals, '0') else ""
        if (decimals > 0) {
            // Optional decimals (#) are dropped when zero.
            var keep = decPart.length
            while (keep > minDecimals && decPart[keep - 1] == '0') keep--
            decPart = decPart.substring(0, keep)
        }
        if (intPart == "0" && minInt == 0) intPart = ""
        intPart = intPart.padStart(minInt, '0')
        if (grouping && intPart.length > 3) intPart = intPart.reversed().chunked(3).joinToString(",").reversed()
        // Literal text between the digits (rare: "000-0000") is kept by copying non-digit characters in order.
        val inner = literal(intPattern.filter { it != '0' && it != '#' && it != '?' && it != ',' }) { null }
        val negative = v < 0 && rounded != 0L
        val body = intPart + inner + (if (decPart.isNotEmpty() || (decimals > 0 && decPattern.startsWith("0"))) ".$decPart" else if (point >= 0 && decimals == 0) "." else "")
        return (if (negative) "-" else "") + before + body + after
    }

    private val EXCEL_EPOCH = LocalDateTime.of(1899, 12, 30, 0, 0)
    private val EPOCH_1904 = LocalDateTime.of(1904, 1, 1, 0, 0)
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val MONTH_NAMES = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val DAY_NAMES = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val KOREAN_DAYS = arrayOf("월", "화", "수", "목", "금", "토", "일")

    /** A date serial number as [section] shows it; null if out of Excel's range. */
    private fun date(serial: Double, section: String, date1904: Boolean): String? {
        if (serial < 0 || serial > 2_958_466) return null
        val millis = (serial * 86_400_000.0).roundToLong()
        val time = if (date1904) EPOCH_1904.plus(millis, ChronoUnit.MILLIS)
        // Excel counts a 29 February 1900 that never was: serials before it are a day off.
        else if (serial < 61) EXCEL_EPOCH.plus(millis + 86_400_000L, ChronoUnit.MILLIS) else EXCEL_EPOCH.plus(millis, ChronoUnit.MILLIS)
        val hasAmPm = section.contains("AM/PM", true) || section.contains("A/P", true) || section.contains("오전/오후")
        val tokens = tokenize(section)
        val out = StringBuilder()
        for ((i, t) in tokens.withIndex()) {
            val lower = t.lowercase()
            when {
                t.startsWith('"') -> out.append(t.removeSurrounding("\""))
                t.startsWith('\\') -> out.append(t.drop(1))
                lower == "am/pm" || lower == "오전/오후" -> out.append(if (time.hour < 12) (if (lower == "am/pm") "AM" else "오전") else (if (lower == "am/pm") "PM" else "오후"))
                lower == "a/p" -> out.append(if (time.hour < 12) "A" else "P")
                lower.startsWith("[") -> {
                    val inside = lower.trim('[', ']')
                    val total = serial * 24
                    when {
                        inside.startsWith("h") -> out.append(floor(total).toLong().toString().padStart(inside.length, '0'))
                        inside.startsWith("m") -> out.append(floor(total * 60).toLong().toString().padStart(inside.length, '0'))
                        inside.startsWith("s") -> out.append(floor(total * 3600).toLong().toString().padStart(inside.length, '0'))
                        inside.startsWith("$") -> out.append(inside.drop(1).substringBefore('-'))
                    }
                }
                lower.startsWith("y") -> out.append(if (lower.length <= 2) "%02d".format(time.year % 100) else time.year.toString())
                lower.startsWith("e") -> out.append(time.year.toString())
                lower.startsWith("m") && isMinute(tokens, i) -> out.append(if (lower.length >= 2) "%02d".format(time.minute) else time.minute.toString())
                lower.startsWith("m") -> out.append(
                    when (lower.length) {
                        1 -> time.monthValue.toString()
                        2 -> "%02d".format(time.monthValue)
                        3 -> MONTHS[time.monthValue - 1]
                        5 -> MONTHS[time.monthValue - 1].take(1)
                        else -> MONTH_NAMES[time.monthValue - 1]
                    }
                )
                lower.startsWith("d") -> out.append(
                    when (lower.length) {
                        1 -> time.dayOfMonth.toString()
                        2 -> "%02d".format(time.dayOfMonth)
                        3 -> DAYS[time.dayOfWeek.value - 1]
                        else -> DAY_NAMES[time.dayOfWeek.value - 1]
                    }
                )
                lower.startsWith("a") && lower.length >= 3 -> out.append(
                    // Korean weekday names: aaa = 월, aaaa = 월요일.
                    KOREAN_DAYS[time.dayOfWeek.value - 1] + if (lower.length >= 4) "요일" else ""
                )
                lower.startsWith("h") -> {
                    val h = if (hasAmPm) (time.hour % 12).let { if (it == 0) 12 else it } else time.hour
                    out.append(if (lower.length >= 2) "%02d".format(h) else h.toString())
                }
                lower.startsWith("s") -> out.append(if (lower.length >= 2) "%02d".format(time.second) else time.second.toString())
                lower.startsWith(".0") -> {
                    val fraction = (time.nano / 1e9)
                    out.append("%.${lower.length - 1}f".format(java.util.Locale.ROOT, fraction).substring(1))
                }
                t == "_" || t.startsWith("_") -> out.append(' ')
                t.startsWith("*") -> Unit
                else -> out.append(t)
            }
        }
        return out.toString()
    }

    /** "m" means minutes right after an hour or before seconds. */
    private fun isMinute(tokens: List<String>, i: Int): Boolean {
        val codes = tokens.withIndex().filter { (_, t) -> t.firstOrNull()?.lowercaseChar() in setOf('y', 'm', 'd', 'h', 's', '[') }
        val at = codes.indexOfFirst { it.index == i }
        val previous = codes.getOrNull(at - 1)?.value?.lowercase()
        val next = codes.getOrNull(at + 1)?.value?.lowercase()
        return previous?.startsWith("h") == true || previous?.startsWith("[h") == true || next?.startsWith("s") == true
    }

    /** A date section as tokens: runs of one code letter, quoted text, escapes, brackets, AM/PM, others one by one. */
    private fun tokenize(section: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < section.length) {
            val c = section[i]
            val rest = section.substring(i)
            when {
                c == '"' -> {
                    val end = section.indexOf('"', i + 1).let { if (it < 0) section.length - 1 else it }
                    tokens += section.substring(i, end + 1)
                    i = end + 1
                }
                c == '\\' && i + 1 < section.length -> { tokens += section.substring(i, i + 2); i += 2 }
                c == '_' && i + 1 < section.length -> { tokens += "_"; i += 2 }
                c == '*' && i + 1 < section.length -> { tokens += "*"; i += 2 }
                c == '[' -> {
                    val end = section.indexOf(']', i).let { if (it < 0) section.length - 1 else it }
                    tokens += section.substring(i, end + 1)
                    i = end + 1
                }
                rest.startsWith("AM/PM", true) -> { tokens += rest.substring(0, 5); i += 5 }
                rest.startsWith("오전/오후") -> { tokens += "오전/오후"; i += 5 }
                rest.startsWith("A/P", true) -> { tokens += rest.substring(0, 3); i += 3 }
                c == '.' && i + 1 < section.length && section[i + 1] == '0' -> {
                    var j = i + 1
                    while (j < section.length && section[j] == '0') j++
                    tokens += section.substring(i, j)
                    i = j
                }
                c.lowercaseChar() in "ymdhsea" -> {
                    var j = i
                    while (j < section.length && section[j].lowercaseChar() == c.lowercaseChar()) j++
                    tokens += section.substring(i, j)
                    i = j
                }
                else -> { tokens += c.toString(); i++ }
            }
        }
        return tokens
    }
}
