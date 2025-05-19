package com.example.calendar2

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.calendar2.databinding.ActivityMainBinding
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val requestcode = 100
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

    private fun showLimitDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_set_limits, null)

        val editMin = dialogView.findViewById<EditText>(R.id.limit_min)
        val editAvg = dialogView.findViewById<EditText>(R.id.limit_avg)
        val editMax = dialogView.findViewById<EditText>(R.id.limit_max)
        val editTotal = dialogView.findViewById<EditText>(R.id.limit_total)

        editMin.setText(sharedPref.getFloat("limit_min", 400f).toString())
        editAvg.setText(sharedPref.getFloat("limit_avg", 500f).toString())
        editMax.setText(sharedPref.getFloat("limit_max", 700f).toString())
        editTotal.setText(sharedPref.getFloat("limit_total", 0f).toString())

        AlertDialog.Builder(this)
            .setTitle("Установить лимиты")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val min = editMin.text.toString().toFloatOrNull() ?: 400f
                val avg = editAvg.text.toString().toFloatOrNull() ?: 500f
                val max = editMax.text.toString().toFloatOrNull() ?: 700f
                val total = editTotal.text.toString().toFloatOrNull() ?: 0f

                with(sharedPref.edit()) {
                    putFloat("limit_min", min)
                    putFloat("limit_avg", avg)
                    putFloat("limit_max", max)
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

        val radio1 = dialogView.findViewById<RadioButton>(R.id.radio_choice1)
        val radio2 = dialogView.findViewById<RadioButton>(R.id.radio_choice2)
        val radio3 = dialogView.findViewById<RadioButton>(R.id.radio_choice3)

        val editTransport = dialogView.findViewById<EditText>(R.id.edit_transport)
        val editShop = dialogView.findViewById<EditText>(R.id.edit_shop)
        val editEntertainment = dialogView.findViewById<EditText>(R.id.edit_entertainment)
        val editOther = dialogView.findViewById<EditText>(R.id.edit_other)

        val min = sharedPref.getFloat("limit_min", 400f).toInt()
        val avg = sharedPref.getFloat("limit_avg", 500f).toInt()
        val max = sharedPref.getFloat("limit_max", 700f).toInt()

        radio1.text = min.toString()
        radio2.text = avg.toString()
        radio3.text = max.toString()

        var lastChecked: RadioButton? = null

        val assignToggle = { button: RadioButton ->
            button.setOnClickListener {
                if (lastChecked == button) {
                    button.isChecked = false
                    lastChecked = null
                } else {
                    lastChecked?.isChecked = false
                    button.isChecked = true
                    lastChecked = button
                }
            }
        }

        assignToggle(radio1)
        assignToggle(radio2)
        assignToggle(radio3)

        AlertDialog.Builder(this)
            .setTitle("Добавить событие")
            .setView(dialogView)
            .setPositiveButton("Добавить") { _, _ ->
                val radioAmount = lastChecked?.text?.toString()?.toDoubleOrNull() ?: 0.0
                val transport = editTransport.text.toString().toDoubleOrNull() ?: 0.0
                val shop = editShop.text.toString().toDoubleOrNull() ?: 0.0
                val entertainment = editEntertainment.text.toString().toDoubleOrNull() ?: 0.0
                val other = editOther.text.toString().toDoubleOrNull() ?: 0.0

                val description = buildString {
                    if (radioAmount > 0) append("Обед: $radioAmount₽\n")
                    if (transport > 0) append("Транспорт: $transport₽\n")
                    if (shop > 0) append("Магазин: $shop₽\n")
                    if (entertainment > 0) append("Развлечения: $entertainment₽\n")
                    if (other > 0) append("Другое: $other₽")
                }

                val total = radioAmount + transport + shop + entertainment + other
                addEventToCalendar("Расходы", description, total)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        binding.apply {
            setLimitButton.setOnClickListener { showLimitDialog() }

            addExpenses.setOnClickListener {
                if (selectedDateMillis == 0L) {
                    showToast("Сначала выберите дату на календаре :)")
                } else {
                    showAddEventDialog()
                }
            }

            calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
                selectedDateMillis = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 0, 0, 0)
                }.timeInMillis
                updateFinancialStatus()
            }

            calendarView.setOnTouchListener { _, _ ->
                if (selectedDateMillis != 0L) {
                    updateFinancialStatus()
                }
                false
            }

            balanceButton.setOnClickListener {
                showBalanceScreen()
            }
        }
    }

    private fun clearCalendarOnce() {
        if (sharedPref.getBoolean("calendar_cleared", false)) return

        val selection = "${CalendarContract.Events.DESCRIPTION} LIKE ?"
        val selectionArgs = arrayOf("%Обед:%") // Твои события с подписями

        contentResolver.delete(CalendarContract.Events.CONTENT_URI, selection, selectionArgs)

        sharedPref.edit().putBoolean("calendar_cleared", true).apply()
        showToast("Старые события очищены")
    }

    private fun updateFinancialStatus() {
        val events = loadEventsForDate(selectedDateMillis)
        val daily = events.sumOf { it.amount }
        val monthTotal = calculateTotalFromMonthStart(selectedDateMillis)
        val balance = getCurrentBalance()
        val remaining = balance - monthTotal

        binding.financialStatus.text = "Траты за день: %.2f₽\nОстаток: %.2f₽".format(daily, remaining)
        binding.eventsTextView.text = if (events.isNotEmpty())
            events.joinToString("\n") { it.description }
        else
            "Событий на выбранную дату нет."
    }

    private fun calculateTotalFromMonthStart(dateMillis: Long): Double {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val day = cal.get(Calendar.DAY_OF_MONTH)
        var total = 0.0
        for (d in 1..day) {
            cal.set(Calendar.DAY_OF_MONTH, d)
            total += loadEventsForDate(cal.timeInMillis).sumOf { it.amount }
        }
        return total
    }

    @SuppressLint("Range")
    private fun loadEventsForDate(dateMillis: Long): List<ExpenseEvent> {
        val events = mutableListOf<ExpenseEvent>()
        val start = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(start.toString(), end.toString())

        val cursor = contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION),
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val title = it.getString(it.getColumnIndex(CalendarContract.Events.TITLE)) ?: ""
                val description = it.getString(it.getColumnIndex(CalendarContract.Events.DESCRIPTION)) ?: ""

                if (!description.contains("Обед") &&
                    !description.contains("Транспорт") &&
                    !description.contains("Магазин") &&
                    !description.contains("Развлечения") &&
                    !description.contains("Другое")
                ) continue

                val amount = Regex("\\d+(?:[.,]\\d+)?")
                    .findAll(description)
                    .mapNotNull { it.value.replace(",", ".").toDoubleOrNull() }
                    .sum()

                events.add(ExpenseEvent(title, description, amount))
            }
        }

        return events
    }

    private fun addEventToCalendar(title: String, description: String, amount: Double) {
        val calendarId = getCalendarId() ?: return showToast("Не удалось найти календарь")

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, selectedDateMillis)
            put(CalendarContract.Events.DTEND, selectedDateMillis + 60 * 60 * 1000)
            put(CalendarContract.Events.TITLE, title)
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
        val cursor = contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(it.getColumnIndex(CalendarContract.Calendars._ID))
            }
        }
        return null
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
                val new = edit.text.toString().toDoubleOrNull() ?: 0.0
                saveCurrentBalance(new)
                updateFinancialStatus()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_CALENDAR)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CALENDAR)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                requestcode
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestcode && grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            showToast("Нужны разрешения для работы с календарем")
        }
    }
}

data class ExpenseEvent(val title: String, val description: String, val amount: Double)