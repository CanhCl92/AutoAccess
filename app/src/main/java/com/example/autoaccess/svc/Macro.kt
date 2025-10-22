package com.example.autoaccess.svc

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/* ===================== Model ===================== */

data class Macro(
    val id: String,
    val version: Int = 1,
    val steps: List<Step>
)

sealed class Step {
    // Tìm ảnh và CHỜ tới khi thấy (engine thường dùng trong vòng chờ)
    data class WaitImage(
        val id: String,
        val minScore: Int = 800,
        val timeoutMs: Long = 2000
    ) : Step()

    // Tìm ảnh một lần (không chờ lâu)
    data class FindImage(
        val id: String,
        val minScore: Int = 800,
        val timeoutMs: Long = 2000
    ) : Step()

    // Chạm vào ảnh (có offset dx,dy nếu muốn lệch tâm ảnh)
    data class TapImage(
        val id: String,
        val minScore: Int = 800,
        val dx: Int = 0,
        val dy: Int = 0
    ) : Step()

    // Vuốt từ tâm ảnh (offset tuỳ chọn)
    data class SwipeImage(
        val id: String,
        val minScore: Int = 800,
        val dx: Int = 0,
        val dy: Int = 0,
        val durMs: Long = 300
    ) : Step()

    // Tác vụ toạ độ thô (không dùng ảnh)
    data class Tap(val x: Float, val y: Float, val durMs: Long = 80) : Step()
    data class Swipe(val x: Float, val y: Float, val x2: Float, val y2: Float, val durMs: Long = 300) : Step()

    object Back : Step()
    object Home : Step()
    object Recent : Step()
}

/* ===================== Parser ===================== */

object MacroParser {
    private const val TAG = "AutoAccess"

    fun parse(obj: JSONObject): Macro {
        val id = obj.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("macro.id is required")
        val version = obj.optInt("version", 1)
        val stepsArr = obj.optJSONArray("steps")
            ?: throw IllegalArgumentException("macro.steps must be an array")
        return Macro(id = id, version = version, steps = parseSteps(stepsArr))
    }

    fun parseSteps(arr: JSONArray): List<Step> {
        val out = ArrayList<Step>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
                ?: throw IllegalArgumentException("steps[$i] must be an object")
            val rawType = o.optString("type", o.optString("op", ""))
            val type = rawType.lowercase()

            try {
                when (type) {
                    // ---- ẢNH → CHỜ / TÌM ----
                    "waitimage", "wait_image", "wait" -> {
                        val id = pickStr(o, "id", "templateId", "name")
                            ?: throw IllegalArgumentException("steps[$i].id/templateId is required")
                        val minScore = pickInt(o, 800, "minScore", "score", "threshold").coerceIn(0, 1000)
                        val timeout = pickLong(o, 2000, "timeoutMs", "timeout", "ms")
                        out += Step.WaitImage(id, minScore, timeout)
                    }
                    "findimage", "find_image", "find", "imagefind" -> {
                        val id = pickStr(o, "id", "templateId", "name")
                            ?: throw IllegalArgumentException("steps[$i].id/templateId is required")
                        val minScore = pickInt(o, 800, "minScore", "score", "threshold").coerceIn(0, 1000)
                        val timeout = pickLong(o, 1500, "timeoutMs", "timeout", "ms")
                        out += Step.FindImage(id, minScore, timeout)
                    }

                    // ---- ẢNH → TAP / SWIPE ----
                    "tapimage", "tap_image", "tapimg" -> {
                        val id = pickStr(o, "id", "templateId", "name")
                            ?: throw IllegalArgumentException("steps[$i].id/templateId is required")
                        val minScore = pickInt(o, 800, "minScore", "score", "threshold").coerceIn(0, 1000)
                        val dx = pickInt(o, 0, "dx", "offsetX")
                        val dy = pickInt(o, 0, "dy", "offsetY")
                        out += Step.TapImage(id, minScore, dx, dy)
                    }
                    "swipeimage", "swipe_image", "swipeimg" -> {
                        val id = pickStr(o, "id", "templateId", "name")
                            ?: throw IllegalArgumentException("steps[$i].id/templateId is required")
                        val minScore = pickInt(o, 800, "minScore", "score", "threshold").coerceIn(0, 1000)
                        val dx = pickInt(o, 0, "dx", "offsetX")
                        val dy = pickInt(o, 0, "dy", "offsetY")
                        val dur = pickLong(o, 300, "dur", "durMs", "ms")
                        out += Step.SwipeImage(id, minScore, dx, dy, dur)
                    }

                    // ---- Toạ độ thô ----
                    "tap" -> {
                        val id = pickStr(o, "id", "templateId", "name")
                        if (id != null) {
                            val minScore = pickInt(o, 800, "minScore", "score", "threshold").coerceIn(0, 1000)
                            val dx = pickInt(o, 0, "dx", "offsetX")
                            val dy = pickInt(o, 0, "dy", "offsetY")
                            out += Step.TapImage(id, minScore, dx, dy)
                        } else {
                            val x = pickFloat(o, "x") ?: throw IllegalArgumentException("steps[$i].x is required")
                            val y = pickFloat(o, "y") ?: throw IllegalArgumentException("steps[$i].y is required")
                            val dur = pickLong(o, 80, "dur", "durMs", "ms")
                            out += Step.Tap(x, y, dur)
                        }
                    }
                    "swipe" -> {
                        val id = pickStr(o, "id", "templateId", "name")
                        if (id != null) {
                            val minScore = pickInt(o, 800, "minScore", "score", "threshold").coerceIn(0, 1000)
                            val dx = pickInt(o, 0, "dx", "offsetX")
                            val dy = pickInt(o, 0, "dy", "offsetY")
                            val dur = pickLong(o, 300, "dur", "durMs", "ms")
                            out += Step.SwipeImage(id, minScore, dx, dy, dur)
                        } else {
                            val x  = pickFloat(o, "x")  ?: throw IllegalArgumentException("steps[$i].x is required")
                            val y  = pickFloat(o, "y")  ?: throw IllegalArgumentException("steps[$i].y is required")
                            val x2 = pickFloat(o, "x2") ?: throw IllegalArgumentException("steps[$i].x2 is required")
                            val y2 = pickFloat(o, "y2") ?: throw IllegalArgumentException("steps[$i].y2 is required")
                            val dur = pickLong(o, 300, "dur", "durMs", "ms")
                            out += Step.Swipe(x, y, x2, y2, dur)
                        }
                    }

                    // ---- Hệ thống ----
                    "back" -> out += Step.Back
                    "home" -> out += Step.Home
                    "recent", "recents", "menu" -> out += Step.Recent

                    else -> throw IllegalArgumentException("unknown step type '$rawType' at steps[$i]")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MacroParser.parseSteps: bad step at index $i -> $o", t)
                throw t
            }
        }
        return out
    }

    /* ---------- helpers đọc giá trị linh hoạt ---------- */
    private fun pickStr(o: JSONObject, vararg keys: String): String? {
        for (k in keys) if (o.has(k)) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v
            if (v != null && v !is JSONObject && v !is JSONArray) return v.toString()
        }
        return null
    }

    private fun pickInt(o: JSONObject, def: Int, vararg keys: String): Int {
        for (k in keys) if (o.has(k)) {
            when (val v = o.opt(k)) {
                is Int -> return v
                is Long -> return v.toInt()
                is Double -> return v.roundToInt()
                is String -> v.toIntOrNull()?.let { return it }
            }
        }
        return def
    }

    private fun pickLong(o: JSONObject, def: Long, vararg keys: String): Long {
        for (k in keys) if (o.has(k)) {
            when (val v = o.opt(k)) {
                is Int -> return v.toLong()
                is Long -> return v
                is Double -> return v.toLong()
                is String -> v.toLongOrNull()?.let { return it }
            }
        }
        return def
    }

    private fun pickFloat(o: JSONObject, key: String): Float? {
        if (!o.has(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull()
            else -> null
        }
    }
}
