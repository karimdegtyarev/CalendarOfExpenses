package com.example.calendar2

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.calendar2.databinding.ActivityMainBinding
import java.util.Calendar
import java.util.TimeZone

data class ExpenseEvent(
    val title: String,
    val description: String,
    val amount: Double
)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val requestCodeCalendar = 100
    private var selectedDateMillis: Long = 0L
    private val sharedPref by lazy { getSharedPreferences("FinancePrefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()
        clearCalendarOnce()
        setupViews()
    }

    private fun setupViews() {
        binding.setLimitButton.setOnClickListener { showLimitDialog() }
        binding.addExpenses.setOnClickListener {
            if (selectedDateMillis == 0L) {
                showToast("Сначала выберите дату на календаре :)")
            } else {
                showAddEventDialog()
            }
        }
        binding.calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            selectedDateMillis = Calendar.getInstance().apply {
                set(year, month, dayOfMonth, 0, 0, 0)
            }.timeInMillis
            updateFinancialStatus()
        }
        binding.calendarView.setOnTouchListener { _, _ ->
            if (selectedDateMillis != 0L) updateFinancialStatus()
            false
        }
        binding.balanceButton.setOnClickListener { showBalanceScreen() }
    }

    private fun showLimitDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_set_limits, null)

        val editMin   = dialogView.findViewById<EditText>(R.id.limit_min)
        val editAvg   = dialogView.findViewById<EditText>(R.id.limit_avg)
        val editMax   = dialogView.findViewById<EditText>(R.id.limit_max)
        val editTotal = dialogView.findViewById<EditText>(R.id.limit_total)

        // Подставляем текущие лимиты
        editMin.setText(sharedPref.getFloat("limit_min", 400f).toString())
        editAvg.setText(sharedPref.getFloat("limit_avg", 500f).toString())
        editMax.setText(sharedPref.getFloat("limit_max", 700f).toString())
        editTotal.setText(sharedPref.getFloat("limit_total", 0f).toString())

        AlertDialog.Builder(this)
            .setTitle("Установить лимиты")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val min   = editMin.text.toString().toFloatOrNull()   ?: 400f
                val avg   = editAvg.text.toString().toFloatOrNull()   ?: 500f
                val max   = editMax.text.toString().toFloatOrNull()   ?: 700f
                val total = editTotal.text.toString().toFloatOrNull() ?: 0f

                with(sharedPref.edit()) {
                    putFloat("limit_min",   min)
                    putFloat("limit_avg",   avg)
                    putFloat("limit_max",   max)
                    putFloat("limit_total", total)
                    apply()
                }

                showToast("Лимиты сохранены")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showAddEventDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_expenses, null)

        val radio1           = dialogView.findViewById<RadioButton>(R.id.radio_choice1)
        val radio2           = dialogView.findViewById<RadioButton>(R.id.radio_choice2)
        val radio3           = dialogView.findViewById<RadioButton>(R.id.radio_choice3)
        val editCustomLunch  = dialogView.findViewById<EditText>(R.id.edit_custom_lunch)
        val editTransport    = dialogView.findViewById<EditText>(R.id.edit_transport)
        val editShop         = dialogView.findViewById<EditText>(R.id.edit_shop)
        val editEntertainment= dialogView.findViewById<EditText>(R.id.edit_entertainment)
        val editOther        = dialogView.findViewById<EditText>(R.id.edit_other)

        // Подставляем лимиты на обеды из настроек
        val min = sharedPref.getFloat("limit_min", 400f).toInt()
        val avg = sharedPref.getFloat("limit_avg", 500f).toInt()
        val max = sharedPref.getFloat("limit_max", 700f).toInt()
        radio1.text = min.toString()
        radio2.text = avg.toString()
        radio3.text = max.toString()

        var lastChecked: RadioButton? = null
        listOf(radio1, radio2, radio3).forEach { btn ->
            btn.setOnClickListener {
                if (lastChecked == btn) {
                    btn.isChecked = false
                    lastChecked = null
                } else {
                    lastChecked?.isChecked = false
                    btn.isChecked = true
                    lastChecked = btn
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Добавить событие")
            .setView(dialogView)
            .setPositiveButton("Добавить") { _, _ ->
                // Сначала «Другая сумма», иначе выбранный лимит
                val radioAmount = lastChecked
                    ?.text
                    ?.toString()
                    ?.toDoubleOrNull() ?: 0.0
                val lunchAmount = editCustomLunch
                    .text
                    .toString()
                    .toDoubleOrNull() ?: radioAmount

                val transport    = editTransport.text.toString().toDoubleOrNull() ?: 0.0
                val shop         = editShop.text.toString().toDoubleOrNull() ?: 0.0
                val entertainment= editEntertainment.text.toString().toDoubleOrNull() ?: 0.0
                val other        = editOther.text.toString().toDoubleOrNull() ?: 0.0

                val description = buildString {
                    if (lunchAmount > 0)    append("Обед: $lunchAmount₽\n")
                    if (transport > 0)      append("Транспорт: $transport₽\n")
                    if (shop > 0)           append("Магазин: $shop₽\n")
                    if (entertainment > 0)  append("Развлечения: $entertainment₽\n")
                    if (other > 0)          append("Другое: $other₽")
                }
                val total = lunchAmount + transport + shop + entertainment + other
                addEventToCalendar("Расходы", description, total)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun updateFinancialStatus() {
        val events   = loadEventsForDate(selectedDateMillis)
        val dailySum = events.sumOf { it.amount }

        val monthTotal = calculateTotalFromMonthStart(selectedDateMillis)
        val balance    = getCurrentBalance()
        val remaining  = balance - monthTotal

        val cal          = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        val daysRemaining= cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH)
        val dailyLimit   = sharedPref.getFloat("limit_total", 1000f)

        var freeMoney = remaining - dailyLimit * daysRemaining
        if (dailySum > 0) freeMoney -= dailyLimit

        binding.financialStatus.text =
            "Траты за день: %.2f₽\nОстаток: %.2f₽\nСвободные деньги: %.2f₽"
                .format(dailySum, remaining, freeMoney)

        binding.eventsTextView.text = if (events.isNotEmpty())
            events.joinToString("\n") { it.description }
        else
            "Событий на выбранную дату нет."
    }

    private fun calculateTotalFromMonthStart(dateMillis: Long): Double {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        return (1..cal.get(Calendar.DAY_OF_MONTH)).sumOf { day ->
            cal.set(Calendar.DAY_OF_MONTH, day)
            loadEventsForDate(cal.timeInMillis).sumOf { it.amount }
        }
    }

    @SuppressLint("Range")
    private fun loadEventsForDate(dateMillis: Long): List<ExpenseEvent> {
        val events = mutableListOf<ExpenseEvent>()
        val start = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION),
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(start.toString(), end.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(cursor.getColumnIndex(CalendarContract.Events.TITLE)) ?: ""
                val desc  = cursor.getString(cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)) ?: ""
                if (!listOf("Обед","Транспорт","Магазин","Развлечения","Другое").any { desc.contains(it) }) continue
                val amt = Regex("\\d+(?:[.,]\\d+)?")
                    .findAll(desc)
                    .mapNotNull { it.value.replace(",",".").toDoubleOrNull() }
                    .sum()
                events.add(ExpenseEvent(title, desc, amt))
            }
        }
        return events
    }

    private fun addEventToCalendar(title: String, description: String, amount: Double) {
        val calendarId = getCalendarId() ?: return showToast("Не удалось найти календарь")
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, selectedDateMillis)
            put(CalendarContract.Events.DTEND,   selectedDateMillis + 60*60*1000)
            put(CalendarContract.Events.TITLE,   title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        try {
            contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            showToast("Событие добавлено!")
            updateFinancialStatus()
        } catch (e: Exception) {
            Log.e("CalendarError", "Ошибка: ${e.message}", e)
            showToast("Ошибка при добавлении события: ${e.message}")
        }
    }

    @SuppressLint("Range")
    private fun getCalendarId(): Long? {
        return contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID), null, null, null
        )?.use {
            if (it.moveToFirst()) it.getLong(it.getColumnIndex(CalendarContract.Calendars._ID)) else null
        }
    }

    private fun getCurrentBalance(): Double =
        sharedPref.getFloat("current_balance", 0f).toDouble()

    private fun saveCurrentBalance(value: Double) {
        sharedPref.edit().putFloat("current_balance", value.toFloat()).apply()
    }

    private fun showBalanceScreen() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_balance, null)
        val edit = dialogView.findViewById<EditText>(R.id.edit_balance)
        edit.setText(getCurrentBalance().toString())
        AlertDialog.Builder(this)
            .setTitle("Управление балансом")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                saveCurrentBalance(edit.text.toString().toDoubleOrNull() ?: 0.0)
                updateFinancialStatus()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun clearCalendarOnce() {
        if (sharedPref.getBoolean("calendar_cleared", false)) return
        contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events.DESCRIPTION} LIKE ?",
            arrayOf("%Обед:%")
        )
        sharedPref.edit().putBoolean("calendar_cleared", true).apply()
        showToast("Старые события очищены")
    }

    private fun showToast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun checkAndRequestPermissions() {
        val needed = listOf(
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CALENDAR
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), requestCodeCalendar)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestCodeCalendar && grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            showToast("Нужны разрешения для работы с календарем")
        }
    }
}

