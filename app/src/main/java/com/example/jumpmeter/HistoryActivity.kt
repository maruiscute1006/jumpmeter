package com.example.jumpmeter

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "점프 기록"
        listView = ListView(this)
        setContentView(listView)
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this)
                .setMessage("이 기록을 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    val list = RecordStore.load(this)
                    val idx = list.size - 1 - pos   // 목록은 최신순
                    if (idx in list.indices) {
                        list.removeAt(idx)
                        RecordStore.saveAll(this, list)
                        refresh()
                    }
                }
                .setNegativeButton("취소", null)
                .show()
            true
        }
        refresh()
    }

    private fun refresh() {
        val list = RecordStore.load(this)
        val best = list.maxByOrNull { it.heightFlightCm }
        supportActionBar?.subtitle =
            if (best != null) "최고 %.1f cm / 총 %d회 (길게 누르면 삭제)".format(best.heightFlightCm, list.size) else null
        val rows = list.reversed().map {
            buildString {
                append("${fmt.format(Date(it.time))}\n")
                append("점프 %.1f cm · 체공 %.3f s · 상승량 %.1f cm".format(it.heightFlightCm, it.flightSec, it.heightDispCm))
                append("\n키 %.0f cm".format(it.userHeightCm))
                if (it.weightKg > 0) append(" · %.1f kg · 파워 %.0f W".format(it.weightKg, it.powerW))
                if (it.memo.isNotEmpty()) append("\n메모: ${it.memo}")
            }
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}
