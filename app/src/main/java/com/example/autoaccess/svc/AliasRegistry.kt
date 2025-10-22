package com.example.autoaccess.svc

import java.util.concurrent.ConcurrentHashMap

/** Lưu alias (tên → chuỗi JSON các bước) trong RAM để engine dùng nhanh. */
object AliasRegistry {
    private val map = ConcurrentHashMap<String, String>()

    fun put(name: String, json: String) { map[name] = json }
    fun get(name: String): String? = map[name]
    fun remove(name: String) { map.remove(name) }
    fun clear() { map.clear() }

    /** Trả bản sao (read-only) cho mục đích liệt kê. */
    fun list(): Map<String, String> = HashMap(map)
}