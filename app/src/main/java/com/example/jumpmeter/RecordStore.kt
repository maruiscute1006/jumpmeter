package com.example.jumpmeter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class JumpRecord(
    val time: Long,
    val flightSec: Double,
    val heightFlightCm: Double,
    val heightDispCm: Double,
    val userHeightCm: Double,
    val weightKg: Double,
    val powerW: Double,
    val memo: String
)

object RecordStore {
    private fun file(c: Context) = File(c.filesDir, "records.json")

    fun load(c: Context): MutableList<JumpRecord> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        val arr = JSONArray(f.readText())
        val list = mutableListOf<JumpRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                JumpRecord(
                    o.getLong("time"), o.getDouble("flightSec"),
                    o.getDouble("heightFlightCm"), o.getDouble("heightDispCm"),
                    o.getDouble("userHeightCm"), o.getDouble("weightKg"),
                    o.getDouble("powerW"), o.getString("memo")
                )
            )
        }
        return list
    }

    fun saveAll(c: Context, list: List<JumpRecord>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("time", it.time); put("flightSec", it.flightSec)
                put("heightFlightCm", it.heightFlightCm); put("heightDispCm", it.heightDispCm)
                put("userHeightCm", it.userHeightCm); put("weightKg", it.weightKg)
                put("powerW", it.powerW); put("memo", it.memo)
            })
        }
        file(c).writeText(arr.toString())
    }

    fun add(c: Context, r: JumpRecord) {
        val list = load(c)
        list.add(r)
        saveAll(c, list)
    }
}
