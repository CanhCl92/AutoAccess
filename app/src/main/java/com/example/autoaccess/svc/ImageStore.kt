package com.example.autoaccess.svc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object ImageStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "images").apply { if (!exists()) mkdirs() }

    fun file(ctx: Context, id: String): File = File(dir(ctx), "$id.png")

    fun exists(ctx: Context, id: String): Boolean = file(ctx, id).exists()

    fun save(ctx: Context, id: String, bytes: ByteArray) {
        file(ctx, id).writeBytes(bytes)   // vẫn mặc định .png như bản của bạn
    }

    data class Info(val outWidth: Int, val outHeight: Int)

    /** Trả về danh sách (id, Info) */
    fun list(ctx: Context): List<Pair<String, Info>> {
        val res = ArrayList<Pair<String, Info>>()
        dir(ctx).listFiles()?.forEach { f ->
            if (f.isFile) {
                val name = f.nameWithoutExtension
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                res += name to Info(opts.outWidth, opts.outHeight)
            }
        }
        return res
    }

    fun delete(ctx: Context, id: String): Boolean = file(ctx, id).let { it.exists() && it.delete() }

    /** Tải bitmap phục vụ matching (engine) */
    fun loadBitmap(ctx: Context, id: String): Bitmap? =
        BitmapFactory.decodeFile(file(ctx, id).absolutePath)
}