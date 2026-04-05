package com.kreativekoala.surfsense

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import java.text.SimpleDateFormat
import java.util.Locale

class TrendsActivity : AppCompatActivity() {
    private lateinit var localStorage: LocalStorage
    private lateinit var periodSpinner: Spinner
    private lateinit var summaryCard: CardView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvAvgTime: TextView
    private lateinit var tvPeakTime: TextView
    private lateinit var dailyChart: BarChart
    private lateinit var categoryChart: HorizontalBarChart
    private lateinit var detailsContainer: LinearLayout
    private lateinit var emptyStateView: LinearLayout

    private var currentPeriod = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trends)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.usage_trends_title)

        localStorage = LocalStorage.getInstance(this)
        initViews()
        setupPeriodSpinner()
        loadData()
    }

    private fun initViews() {
        periodSpinner = findViewById(R.id.periodSpinner)
        summaryCard = findViewById(R.id.summaryCard)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        tvAvgTime = findViewById(R.id.tvAvgTime)
        tvPeakTime = findViewById(R.id.tvPeakTime)
        dailyChart = findViewById(R.id.dailyUsageChart)
        categoryChart = findViewById(R.id.categoryChart)
        detailsContainer = findViewById(R.id.detailsContainer)
        emptyStateView = findViewById(R.id.emptyStateView)
    }

    private fun setupPeriodSpinner() {
        val periods = arrayOf(getString(R.string.period_7_days), getString(R.string.period_14_days), getString(R.string.period_30_days))
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, periods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        periodSpinner.adapter = adapter

        periodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentPeriod = when (position) {
                    0 -> 7
                    1 -> 14
                    else -> 30
                }
                loadData()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadData() {
        val history = localStorage.getUsageHistory(currentPeriod)

        if (history.isEmpty()) {
            showEmptyState()
            return
        }

        hideEmptyState()
        updateSummaryCard(history)
        updateDailyChart(history)
        updateCategoryChart(history)
        updateDetailsList(history)
    }

    private fun showEmptyState() {
        emptyStateView.visibility = View.VISIBLE
        summaryCard.visibility = View.GONE
        dailyChart.visibility = View.GONE
        categoryChart.visibility = View.GONE
        detailsContainer.visibility = View.GONE
    }

    private fun hideEmptyState() {
        emptyStateView.visibility = View.GONE
        summaryCard.visibility = View.VISIBLE
        dailyChart.visibility = View.VISIBLE
        categoryChart.visibility = View.VISIBLE
        detailsContainer.visibility = View.VISIBLE
    }

    private fun updateSummaryCard(history: List<LocalStorage.DailyUsage>) {
        val totalMinutes = history.sumOf { it.totalMinutes }
        val avgMinutes = if (history.isNotEmpty()) totalMinutes / history.size else 0
        val peakMinutes = history.maxOfOrNull { it.totalMinutes } ?: 0

        tvTotalTime.text = formatMinutes(totalMinutes)
        tvAvgTime.text = formatMinutes(avgMinutes)
        tvPeakTime.text = formatMinutes(peakMinutes)
    }

    private fun updateDailyChart(history: List<LocalStorage.DailyUsage>) {
        val reversedHistory = history.reversed()
        val entries = reversedHistory.mapIndexed { index, usage ->
            BarEntry(index.toFloat(), usage.totalMinutes.toFloat())
        }

        val dataSet = BarDataSet(entries, "Minutes").apply {
            colors = listOf(Color.parseColor("#3B82F6"))
            valueTextSize = 10f
        }

        dailyChart.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(reversedHistory.map { formatDateShort(it.date) })
                granularity = 1f
                setDrawGridLines(false)
            }

            axisLeft.apply {
                axisMinimum = 0f
                setDrawGridLines(true)
            }
            axisRight.isEnabled = false

            setFitBars(true)
            animateY(500)
            invalidate()
        }
    }

    private fun updateCategoryChart(history: List<LocalStorage.DailyUsage>) {
        // Aggregate category totals
        val categoryTotals = mutableMapOf<String, Int>()
        history.forEach { day ->
            day.byCategory.forEach { (category, minutes) ->
                categoryTotals[category] = (categoryTotals[category] ?: 0) + minutes
            }
        }

        val sortedCategories = categoryTotals.entries.sortedByDescending { it.value }
        val entries = sortedCategories.mapIndexed { index, entry ->
            BarEntry(index.toFloat(), entry.value.toFloat())
        }

        val colors = listOf(
            Color.parseColor("#3B82F6"), // Blue
            Color.parseColor("#10B981"), // Green
            Color.parseColor("#8B5CF6"), // Purple
            Color.parseColor("#F97316"), // Orange
            Color.parseColor("#6B7280")  // Gray
        )

        val dataSet = BarDataSet(entries, "Minutes by Category").apply {
            this.colors = colors.take(entries.size).ifEmpty { listOf(Color.GRAY) }
            valueTextSize = 10f
        }

        categoryChart.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(sortedCategories.map { it.key })
                granularity = 1f
                setDrawGridLines(false)
            }

            axisLeft.apply {
                axisMinimum = 0f
                setDrawGridLines(true)
            }
            axisRight.isEnabled = false

            setFitBars(true)
            animateY(500)
            invalidate()
        }
    }

    private fun updateDetailsList(history: List<LocalStorage.DailyUsage>) {
        detailsContainer.removeAllViews()

        // Title
        val titleView = TextView(this).apply {
            text = getString(R.string.daily_details)
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 32, 0, 16)
        }
        detailsContainer.addView(titleView)

        // Show first 7 days in detail list
        history.take(7).forEach { day ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
            }

            val leftColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val dateText = TextView(this).apply {
                text = formatDateFull(day.date)
                textSize = 14f
                setTextColor(Color.BLACK)
            }

            val topCategory = day.byCategory.maxByOrNull { it.value }
            val categoryText = TextView(this).apply {
                text = topCategory?.let { "${it.key}: ${formatMinutes(it.value)}" } ?: getString(R.string.no_data)
                textSize = 12f
                setTextColor(Color.GRAY)
            }

            leftColumn.addView(dateText)
            leftColumn.addView(categoryText)

            val timeText = TextView(this).apply {
                text = formatMinutes(day.totalMinutes)
                textSize = 16f
                setTextColor(Color.parseColor("#3B82F6"))
            }

            row.addView(leftColumn)
            row.addView(timeText)
            detailsContainer.addView(row)

            // Divider
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                )
                setBackgroundColor(Color.parseColor("#E5E7EB"))
            }
            detailsContainer.addView(divider)
        }
    }

    private fun formatMinutes(minutes: Int): String {
        return if (minutes >= 60) {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
        } else {
            "${minutes}m"
        }
    }

    private fun formatDateShort(dateString: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("MM/dd", Locale.US)
            val date = inputFormat.parse(dateString)
            date?.let { outputFormat.format(it) } ?: dateString
        } catch (e: Exception) {
            dateString
        }
    }

    private fun formatDateFull(dateString: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("EEEE, MMM d", Locale.US)
            val date = inputFormat.parse(dateString)
            date?.let { outputFormat.format(it) } ?: dateString
        } catch (e: Exception) {
            dateString
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
