package com.example.jumpmeter

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 학년별로 점프 높이 순위를 보여준다. (길게 누르면 삭제) */
class HistoryActivity : AppCompatActivity() {

    private lateinit var spinner: Spinner
    private lateinit var listView: ListView
    private var shown: List<JumpRecord> = emptyList()

    // 0 ~ 5 = 1~6학년, 6 = 전체
    private val choices = (1..6).map { "${it}학년" } + "전체"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "점프 순위"

        spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        val prefs = getSharedPreferences("jump", MODE_PRIVATE)
        spinner.setSelection((prefs.getInt("grade", 1) - 1).coerceIn(0, 5))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        listView = ListView(this)
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val target = shown.getOrNull(pos) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setMessage("${target.memo} 기록을 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    val list = RecordStore.load(this)
                    list.removeAll { it.time == target.time }
                    RecordStore.saveAll(this, list)
                    refresh()
                }
                .setNegativeButton("취소", null)
                .show()
            true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(spinner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(listView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun refresh() {
        val sel = spinner.selectedItemPosition
        val all = RecordStore.load(this)
        val filtered = if (sel in 0..5) all.filter { it.grade == sel + 1 } else all
        shown = filtered.sortedByDescending { it.heightFlightCm }
        supportActionBar?.subtitle = "${choices[sel]} ${shown.size}회 (길게 누르면 삭제)"
        val rows = shown.mapIndexed { i, r ->
            val g = if (sel == 6 && r.grade > 0) "${r.grade}학년 " else ""
            val c = if (r.classNo > 0) "${r.classNo}반 " else ""
            "%d위   %s%s%s   %.1f cm".format(i + 1, g, c, r.memo, r.heightFlightCm)
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}
