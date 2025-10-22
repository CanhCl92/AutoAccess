package com.example.autoaccess.svc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.autoaccess.cap.ScreenCapture
import com.example.autoaccess.cap.ScreenCaptureService
import com.example.autoaccess.ui.CapturePermissionActivity
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("StaticFieldLeak")
object HttpServer {

    private var server: Srv? = null
    private lateinit var appCtx: Context
    private val started = AtomicBoolean(false)

    private val engine = GestureEngine()

    fun start(ctx: Context, port: Int = 8765) {
        if (started.get()) return
        appCtx = ctx.applicationContext
        val s = Srv(appCtx, port, engine)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = s
        started.set(true)
        Log.i("AutoAccess", "HttpServer started on $port")
    }

    fun stop() {
        try { server?.stop() } catch (_: Throwable) {}
        server = null
        started.set(false)
        Log.i("AutoAccess", "HttpServer stopped")
    }

    fun isRunning(): Boolean = started.get()
    fun startAsync(ctx: Context) = start(ctx)
    fun stopSafe() = stop()

    private class Srv(
        private val appCtx: Context,
        port: Int,
        private val engine: GestureEngine
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                val path = session.uri ?: "/"
                val method = session.method
                if (method == Method.OPTIONS) {
                    return withCors(
                        newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "")
                    )
                }
                when {
                    method == Method.GET  && path == "/ping"            -> jsonOk("""{"ok":true}""")
                    method == Method.GET  && path == "/status"          -> handleStatus()
                    method == Method.POST && path == "/macro"           -> handleMacro(session)
                    method == Method.POST && path == "/run"             -> handleRun(session)
                    method == Method.POST && path == "/stop"            -> handleStop()

                    method == Method.POST && path == "/alias"           -> handleAliasSave(session)
                    method == Method.GET  && path == "/alias"           -> handleAliasList()

                    method == Method.POST && path == "/image"           -> handleImageUpload(session)
                    method == Method.GET  && path == "/image"           -> handleImageList()
                    method == Method.DELETE && path == "/image"         -> handleImageDelete(session)

                    method == Method.POST && path == "/cmd"             -> handleCmd(session)

                    method == Method.GET  && path == "/capture"         -> handleCaptureStatus()
                    method == Method.POST && path == "/capture/start"   -> handleCaptureStart()
                    method == Method.POST && path == "/capture/stop"    -> handleCaptureStop()

                    // ---------- DEBUG ----------
                    method == Method.GET  && path == "/debug/last"      -> handleDebugLast()
                    method == Method.GET  && path == "/debug/overlay"   -> handleDebugOverlay()
                    method == Method.GET  && path == "/debug/crop"      -> handleDebugCrop()
                    method == Method.GET && path == "/capture/snap" -> handleCaptureSnap()
                    method == Method.GET  && path == "/sizes"  -> handleSizes()

                    else -> jsonError(404, "not_found", "No route for $method $path")
                }


            } catch (t: Throwable) {
                Log.e("AutoAccess", "serve failed", t)
                jsonError(500, "internal_error", t.message ?: "error")
            }

        }

        private fun handleStatus(): Response {
            val jo = JSONObject()
                .put("running", engine.isRunning())
                .put("id", engine.currentMacroId)
                .put("step", engine.currentStep)
            return jsonOk(jo.toString())
        }
        private fun handleSizes(): Response {
            val (cw, ch) = ScreenCapture.frameSize()
            val di = AccessibilitySvc.instance?.displayInfo()
            val jo = org.json.JSONObject().apply {
                put("ready", ScreenCapture.isReady())
                put("captureW", cw)
                put("captureH", ch)
                if (di != null) {
                    put("displayW", di.w)
                    put("displayH", di.h)
                    put("insets", org.json.JSONObject().apply {
                        put("l", di.insetLeft)
                        put("t", di.insetTop)
                        put("r", di.insetRight)
                        put("b", di.insetBottom)
                    })
                    put("effW", di.w - di.insetLeft - di.insetRight)
                    put("effH", di.h - di.insetTop  - di.insetBottom)
                    put("orientation", if ((di.w >= di.h)) "landscape" else "portrait")
                } else {
                    val (dw, dh) = AccessibilitySvc.instance?.displaySize() ?: (0 to 0)
                    put("displayW", dw)
                    put("displayH", dh)
                    put("insets", org.json.JSONObject().apply { put("l",0);put("t",0);put("r",0);put("b",0) })
                    put("effW", dw)
                    put("effH", dh)
                    put("orientation", if (dw >= dh) "landscape" else "portrait")
                }
            }
            return jsonOk(jo.toString())
        }

        /** PATCH: chấp nhận cả {"id":...} lẫn {"macro":{ "id":... }} */
        private fun handleMacro(session: IHTTPSession): Response {
            val body = readBodyText(session)
            if (body.isBlank()) return jsonError(400, "bad_request", "empty body")

            val raw = try { JSONObject(body) } catch (t: Throwable) {
                Log.e("AutoAccess", "/macro invalid JSON: $body", t)
                return jsonError(400, "bad_request", "invalid json")
            }

            val obj = when {
                raw.has("id") -> raw
                raw.optJSONObject("macro") != null -> raw.getJSONObject("macro")
                raw.optJSONObject("data")  != null -> raw.getJSONObject("data")
                else -> raw
            }

            if (!obj.has("id")) {
                Log.e("AutoAccess", "/macro missing id. raw=$raw")
                return jsonError(400, "bad_request", "missing id")
            }

            val macro = try {
                MacroParser.parse(obj)
            } catch (t: Throwable) {
                Log.e("AutoAccess", "MacroParser.parse failed, obj=$obj", t)
                return jsonError(500, "internal_error", t.message ?: "parse failed")
            }

            val sp = appCtx.getSharedPreferences("macros", Context.MODE_PRIVATE)
            sp.edit().putString(macro.id, obj.toString()).apply()

            Log.i("AutoAccess", "macro saved id=${macro.id} steps=${macro.steps.size}")
            val out = JSONObject().put("ok", true).put("id", macro.id).put("version", macro.version).toString()
            return jsonOk(out)
        }

        private fun handleRun(session: IHTTPSession): Response {
            val body = readBodyText(session)
            val obj = if (body.isEmpty()) JSONObject() else JSONObject(body)
            val id = obj.optString("id", "")
            if (id.isBlank()) return jsonError(400, "bad_request", "missing id")

            val sp = appCtx.getSharedPreferences("macros", Context.MODE_PRIVATE)
            val raw = sp.getString(id, null) ?: return jsonError(404, "not_found", "macro $id not found")
            val macro = MacroParser.parse(JSONObject(raw))

            engine.run(macro)
            return jsonOk(JSONObject().put("ok", true).put("running", true).put("id", id).toString())
        }

        private fun handleStop(): Response {
            engine.stop()
            return jsonOk("""{"ok":true,"running":false}""")
        }

        private fun handleAliasSave(session: IHTTPSession): Response {
            val body = readBodyText(session)
            if (body.isEmpty()) return jsonError(400, "bad_request", "empty body")
            val obj = JSONObject(body)
            val name = obj.getString("name")

            val stepsJson: String = if (obj.has("steps")) {
                val steps = obj.getJSONArray("steps")
                JSONObject().put("steps", steps).toString()
            } else {
                obj.optString("contentJson", "{\"steps\":[]}")
            }

            AliasRegistry.put(name, stepsJson)

            val sp = appCtx.getSharedPreferences("aliases", Context.MODE_PRIVATE)
            sp.edit().putString(name, stepsJson).apply()

            return jsonOk(JSONObject().put("ok", true).put("name", name).toString())
        }

        private fun handleAliasList(): Response {
            val sp = appCtx.getSharedPreferences("aliases", Context.MODE_PRIVATE)
            val names = JSONArray()
            for (e in sp.all.keys) names.put(e)
            return jsonOk(JSONObject().put("aliases", names).toString())
        }

        private fun handleImageUpload(session: IHTTPSession): Response {
            val id = session.parms["id"] ?: ""
            if (id.isBlank()) return jsonError(400, "bad_request", "missing id")
            val bytes = readBodyBytes(session)
            if (bytes.isEmpty()) return jsonError(400, "bad_request", "empty content")
            ImageStore.save(appCtx, id, bytes)
            return jsonOk("""{"ok":true,"id":"$id"}""")
        }

        private fun handleImageList(): Response {
            val arr = JSONArray()
            for ((name, info) in ImageStore.list(appCtx)) {
                arr.put(JSONObject().put("id", name).put("w", info.outWidth).put("h", info.outHeight))
            }
            return jsonOk(JSONObject().put("images", arr).toString())
        }

        private fun handleImageDelete(session: IHTTPSession): Response {
            val id = session.parms["id"] ?: ""
            if (id.isBlank()) return jsonError(400, "bad_request", "missing id")
            return jsonOk("""{"ok":${ImageStore.delete(appCtx, id)}}""")
        }

        private fun handleCmd(session: IHTTPSession): Response {
            val body = readBodyText(session)
            if (body.isEmpty()) return jsonError(400, "bad_request", "empty body")
            val jo = org.json.JSONObject(body)

            val op    = jo.optString("op", "").lowercase(java.util.Locale.ROOT)
            val space = jo.optString("space", "disp")  // "cap" | "disp"
            val x  = jo.optDouble("x", Double.NaN)
            val y  = jo.optDouble("y", Double.NaN)
            val x2 = jo.optDouble("x2", Double.NaN)
            val y2 = jo.optDouble("y2", Double.NaN)
            val dur = if (jo.has("dur")) jo.optLong("dur", 1L) else jo.optLong("ms", 1L)

            val svc = AccessibilitySvc.instance ?: return jsonError(503, "service_unavailable", "accessibility off")

            // map nếu tọa độ đang ở không gian capture
            fun mapIfCap(px: Double, py: Double): Pair<Float, Float> {
                if (space != "cap") return px.toFloat() to py.toFloat()
                val (cw, ch) = com.example.autoaccess.cap.ScreenCapture.frameSize()
                val (dw, dh) = svc.displaySize()
                if (cw > 0 && ch > 0 && dw > 0 && dh > 0) {
                    val gx = (px * dw / cw).toFloat()
                    val gy = (py * dh / ch).toFloat()
                    return gx to gy
                }
                return px.toFloat() to py.toFloat()
            }

            return when (op) {
                "tap" -> {
                    if (x.isNaN() || y.isNaN()) return jsonError(400, "bad_request", "tap needs x,y")
                    val (gx, gy) = mapIfCap(x, y)
                    val scheduled = svc.tap(gx, gy, dur.coerceAtLeast(1L))
                    jsonOk(org.json.JSONObject().put("ok", scheduled).put("scheduled", scheduled).toString())
                }
                "swipe" -> {
                    if (x.isNaN() || y.isNaN() || x2.isNaN() || y2.isNaN())
                        return jsonError(400, "bad_request", "swipe needs x,y,x2,y2")
                    val (g1x, g1y) = mapIfCap(x, y)
                    val (g2x, g2y) = mapIfCap(x2, y2)
                    val scheduled = svc.swipe(g1x, g1y, g2x, g2y, dur.coerceAtLeast(1L))
                    jsonOk(org.json.JSONObject().put("ok", scheduled).put("scheduled", scheduled).toString())
                }
                "back"   -> { svc.back();   jsonOk("""{"ok":true}""") }
                "home"   -> { svc.home();   jsonOk("""{"ok":true}""") }
                "recent", "recents", "menu" -> { svc.recent(); jsonOk("""{"ok":true}""") }
                else -> jsonError(400, "bad_request", "unknown op")
            }
        }

        // -------------- DEBUG HANDLERS ----------------

        private fun handleDebugLast(): Response {
            val s = DebugStore.snapshot()
            val jo = JSONObject().put("has", s != null)
            if (s != null) {
                jo.put("id", s.id)
                    .put("x", s.x)
                    .put("y", s.y)
                    .put("score", s.score)
                    .put("ts", s.ts)
            }
            return jsonOk(jo.toString())
        }
        private fun handleCaptureSnap(): Response {
            val bytes = ScreenCapture.lastAsPng() ?: return jsonError(503, "no_frame", "no frame yet")
            return pngOk(bytes) // nhớ thêm CORS header
        }

        private fun handleDebugOverlay(): Response {
            val bytes = DebugStore.overlayPng() ?: return jsonError(404, "not_found", "no overlay")
            return pngOk(bytes)
        }

        private fun handleDebugCrop(): Response {
            val bytes = DebugStore.cropPng() ?: return jsonError(404, "not_found", "no crop")
            return pngOk(bytes)
        }

        // -------------- helpers --------------

        private fun withCors(r: Response): Response {
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "Content-Type")
            return r
        }

        private fun contentTypeJson(): String = "application/json; charset=utf-8"

        private fun jsonOk(s: String): Response =
            withCors(newFixedLengthResponse(Response.Status.OK, contentTypeJson(), s))

        private fun jsonError(code: Int, err: String, msg: String): Response {
            val st = when (code) {
                400 -> Response.Status.BAD_REQUEST
                404 -> Response.Status.NOT_FOUND
                503 -> Response.Status.SERVICE_UNAVAILABLE
                else -> Response.Status.INTERNAL_ERROR
            }
            val jo = org.json.JSONObject().put("error", err).put("message", msg).toString()
            return withCors(newFixedLengthResponse(st, contentTypeJson(), jo))
        }

        private fun pngOk(bytes: ByteArray): Response {
            // NanoHTTPD cần stream + length cho binary
            val ins = java.io.ByteArrayInputStream(bytes)
            return withCors(newFixedLengthResponse(Response.Status.OK, "image/png", ins, bytes.size.toLong()))
        }

        // ----------- body readers -----------

        private fun readBodyText(session: IHTTPSession): String {
            val files = mutableMapOf<String, String>()
            return try {
                session.parseBody(files)
                val p = files["postData"]
                if (p != null) {
                    val f = File(p)
                    if (f.exists()) f.readText() else p
                } else {
                    val first = files.values.firstOrNull()
                    if (first != null) File(first).readText() else ""
                }
            } catch (t: Throwable) {
                Log.w("AutoAccess", "readBodyText failed", t)
                val len = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (len > 0) {
                    val buf = ByteArray(len)
                    var read = 0
                    val ins = session.inputStream
                    while (read < len) {
                        val r = ins.read(buf, read, len - read)
                        if (r <= 0) break
                        read += r
                    }
                    String(buf, 0, read, Charset.forName("UTF-8"))
                } else ""
            }
        }

        private fun readBodyBytes(session: IHTTPSession): ByteArray {
            val ct = session.headers["content-type"]?.lowercase(Locale.ROOT) ?: ""
            if (ct.contains("octet-stream") || ct.startsWith("image/")) {
                val len = session.headers["content-length"]?.toIntOrNull() ?: -1
                if (len > 0) {
                    val buf = ByteArray(len)
                    var read = 0
                    val ins = session.inputStream
                    while (read < len) {
                        val r = ins.read(buf, read, len - read)
                        if (r <= 0) break
                        read += r
                    }
                    return buf.copyOf(read)
                }
            }
            val files = mutableMapOf<String, String>()
            return try {
                session.parseBody(files)
                files["postData"]?.let { p -> File(p).takeIf(File::exists)?.readBytes() } ?:
                files.values.firstOrNull()?.let { p -> File(p).takeIf(File::exists)?.readBytes() } ?:
                ByteArray(0)
            } catch (t: Throwable) {
                Log.w("AutoAccess", "readBodyBytes failed", t)
                ByteArray(0)
            }
        }

        private fun handleCaptureStatus(): Response {
            val ready = ScreenCapture.isReady()
            return jsonOk(JSONObject().put("ready", ready).toString())
        }

        private fun handleCaptureStart(): Response {
            val i = Intent(appCtx, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appCtx.startActivity(i)
            return jsonOk("""{"ok":true,"asking":true}""")
        }

        private fun handleCaptureStop(): Response {
            val i = Intent(appCtx, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_STOP)
            appCtx.startService(i)
            return jsonOk("""{"ok":true}""")
        }
    }
}
