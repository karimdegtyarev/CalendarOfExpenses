package com.example.calendar2

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.addTextChangedListener
import com.applandeo.materialcalendarview.CalendarDay
import com.applandeo.materialcalendarview.EventDay
import com.applandeo.materialcalendarview.listeners.OnCalendarDayClickListener
import com.example.calendar2.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

                sharedPref.edit {
                    putFloat("limit_min", min)
                    putFloat("limit_avg", avg)
                    putFloat("limit_max", max)
                    putFloat("limit_total", total)
                }
                updateCalendarStyles()
                updateFinancialStatusForSelectedDate()
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
        val editCustomLunch = dialogView.findViewById<EditText>(R.id.edit_custom_lunch)
        val editTransport = dialogView.findViewById<EditText>(R.id.edit_transport)
        val editShop = dialogView.findViewById<EditText>(R.id.edit_shop)
        val editEntertainment = dialogView.findViewById<EditText>(R.id.edit_entertainment)
        val editOther = dialogView.findViewById<EditText>(R.id.edit_other)

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
            .setTitle("Добавить трату")
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

                val transport = editTransport.text.toString().toDoubleOrNull() ?: 0.0
                val shop = editShop.text.toString().toDoubleOrNull() ?: 0.0
                val entertainment = editEntertainment.text.toString().toDoubleOrNull() ?: 0.0
                val other = editOther.text.toString().toDoubleOrNull() ?: 0.0

                val description = buildString {
                    if (lunchAmount > 0) append("Обед: $lunchAmount₽\n")
                    if (transport > 0) append("Транспорт: $transport₽\n")
                    if (shop > 0) append("Магазин: $shop₽\n")
                    if (entertainment > 0) append("Развлечения: $entertainment₽\n")
                    if (other > 0) append("Другое: $other₽")
                }
                val total = lunchAmount + transport + shop + entertainment + other
                addEventToCalendar(description, total)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }


    private fun updateCalendarStyles() {
        try {
            if (!::binding.isInitialized) return

            val calendar = binding.calendarView
            val events = mutableListOf<EventDay>()
            val dailyLimit = sharedPref.getFloat("limit_total", 1000f).toDouble()

            cachedEvents.forEach { (dateMillis, eventsList) ->
                try {
                    val dailyTotal = eventsList.sumOf { it.amount }
                    val color = try {
                        getColorResourceBasedOnLimit(dailyTotal, dailyLimit)
                    } catch (e: Exception) {
                        Log.e("ColorCalc", "Error calculating color", e)
                        ContextCompat.getColor(this, R.color.fully_green) // Fallback цвет
                    }
                    val dayCalendar = Calendar.getInstance().apply { timeInMillis = dateMillis }
                    events.add(EventDay(dayCalendar, color))
                } catch (e: Exception) {
                    Log.e("CalendarStyle", "Error processing day events", e)
                }
            }

            calendar.setEvents(events)
        } catch (e: Exception) {
            Log.e("CalendarStyle", "Error updating calendar styles", e)
        }
    }

    private var cachedEvents: Map<Long, List<ExpenseEvent>> = mutableMapOf()
    private var lastMonthCalculated: Int = -1

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
            binding.addIncomeButton.setOnClickListener {
                showAddIncomeDialog()
            }

            binding.calendarView.setOnCalendarDayClickListener(object : OnCalendarDayClickListener {
                override fun onClick(calendarDay: CalendarDay) {
                    selectedDateMillis = calendarDay.calendar.timeInMillis
                    updateFinancialStatusForSelectedDate()
                }
            })

            balanceButton.setOnClickListener {
                showBalanceScreen()
            }

            binding.resetDataButton.setOnClickListener {
                resetAllData()
            }
        }
        updateFinancialStatus()

        // Загрузка данных для текущего месяца в фоновом потоке
        loadMonthDataAsync(Calendar.getInstance().timeInMillis)
    }


    // Обновлённый showAddIncomeDialog с заменой значений и пересчётом свободных денег и остатка
    @SuppressLint("MissingInflatedId")
    private fun showAddIncomeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_income, null)
        val editIncome = dialogView.findViewById<EditText>(R.id.edit_income)

        AlertDialog.Builder(this)
            .setTitle("Добавить доход")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val addedIncome = editIncome.text.toString().toFloatOrNull() ?: 0f

                if (addedIncome <= 0f) {
                    showToast("Введите корректную сумму дохода")
                    return@setPositiveButton
                }

                val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                val currentKey = dateFormat.format(Date(selectedDateMillis))

                // Обновляем доход по дате
                val incomeKey = "income_$currentKey"
                val existingIncome = sharedPref.getFloat(incomeKey, 0f)
                sharedPref.edit().putFloat(incomeKey, existingIncome + addedIncome).apply()

                // Обновляем общий остаток и свободные деньги
                val updatedMoneyLeft = sharedPref.getFloat("money_left", 0f) + addedIncome
                val updatedFreeMoney = sharedPref.getFloat("free_money", 0f) + addedIncome

                sharedPref.edit().apply {
                    putFloat("money_left", updatedMoneyLeft)
                    putFloat("free_money", updatedFreeMoney)
                    apply()
                }

                showToast("Доход добавлен: $addedIncome₽")
                updateFinancialStatus()
                updateFinancialStatusForSelectedDate()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }


    // Обновлённый showAddMonthlyExpenseDialog с заменой значений и пересчётом
    private fun showAddMonthlyExpenseDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_monthly_expense, null)
        val editZhkh = dialogView.findViewById<EditText>(R.id.edit_zhkh)
        val editMortgage = dialogView.findViewById<EditText>(R.id.edit_mortgage)
        val editCredit = dialogView.findViewById<EditText>(R.id.edit_credit)
        val editOther = dialogView.findViewById<EditText>(R.id.edit_other_expense)

        AlertDialog.Builder(this)
            .setTitle("Добавить ежемес. расход")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val zhkh = editZhkh.text.toString().toFloatOrNull() ?: 0f
                val mortgage = editMortgage.text.toString().toFloatOrNull() ?: 0f
                val credit = editCredit.text.toString().toFloatOrNull() ?: 0f
                val other = editOther.text.toString().toFloatOrNull() ?: 0f

                sharedPref.edit().apply {
                    putFloat("monthly_zhkh", zhkh)
                    putFloat("monthly_mortgage", mortgage)
                    putFloat("monthly_credit", credit)
                    putFloat("monthly_other", other)
                    apply()
                }

                updateFinancialStatus()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateFinancialStatus() {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val currentKey = dateFormat.format(Date(selectedDateMillis))
        val events = loadEventsForDate(selectedDateMillis)

        // Значения
        val incomeToday = sharedPref.getFloat("income_$currentKey", 0f)
        val dailySpend = sharedPref.getFloat("day_$currentKey", 0f)
        val dailyLimit = sharedPref.getFloat("limit_total", 0f)

        val moneyLeft = sharedPref.getFloat("money_left", 0f)
        val freeMoney = sharedPref.getFloat("free_money", 0f)

        binding.financialStatus.text = """
        Доход: %.2f₽
        Траты за день: %.2f₽
        Лимит: %.2f₽
        Остаток: %.2f₽
        Свободные деньги: %.2f₽
    """.trimIndent().format(incomeToday, dailySpend, dailyLimit, moneyLeft, freeMoney)

        binding.eventsTextView.text = if (events.isNotEmpty())
            events.joinToString("\n") { it.description }
        else
            "Событий на выбранную дату нет."
    }

    private fun calculateTotalIncomeToDate(dateMillis: Long): Float {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        var total = 0f

        for (day in 1..cal.get(Calendar.DAY_OF_MONTH)) {
            cal.set(Calendar.DAY_OF_MONTH, day)
            val key = "income_${dateFormat.format(cal.time)}"
            total += sharedPref.getFloat(key, 0f)
        }

        return total
    }

    private fun updateFinancialStatusForSelectedDate() {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = selectedDateMillis

        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val currentKey = dateFormat.format(Date(selectedDateMillis))

        // Доход за выбранный день
        val incomeToday = sharedPref.getFloat("income_$currentKey", 0f)

        // Все события (траты) за выбранную дату
        val events = cachedEvents[getDayStartMillis(selectedDateMillis)] ?: emptyList()
        val daily = events.sumOf { it.amount }

        // ✅ Траты с начала месяца по дату
        val monthTotal = calculateTotalFromMonthStart(selectedDateMillis)

        // ✅ Доходы с начала месяца по дату
        val totalIncome = calculateTotalIncomeToDate(selectedDateMillis)

        // ✅ Начальный баланс
        val balance = sharedPref.getFloat("balance_manual", 0f)

        // ✅ Точный остаток = начальный + доходы - траты
        val remaining = balance + totalIncome - monthTotal

        // ✅ Дней до конца месяца
        val daysRemaining =
            calendar.getActualMaximum(Calendar.DAY_OF_MONTH) - calendar.get(Calendar.DAY_OF_MONTH)

        // ✅ Лимит
        val dailyLimit = sharedPref.getFloat("limit_total", 1000f)

        // ✅ Расчёт свободных денег
        var freeMoney = remaining - dailyLimit * daysRemaining
        if (daily > 0) freeMoney -= dailyLimit

// Обновляем UI
        binding.financialStatus.text = """
        Доход: %.2f₽
        Траты за день: %.2f₽
        Лимит: %.2f₽
        Остаток: %.2f₽
        Свободные деньги: %.2f₽
    """.trimIndent().format(incomeToday, daily, dailyLimit, remaining, freeMoney)

        binding.eventsTextView.text = if (events.isNotEmpty())
            events.joinToString("\n") { it.description }
        else
            "Событий на выбранную дату нет."
    }


    private fun getColorResourceBasedOnLimit(dailyTotal: Double, limit: Double): Int {
        return when {
            limit <= 0 -> R.color.fully_green
            else -> {
                val ratio = dailyTotal / limit
                when {
                    ratio <= 1.0 -> R.color.fully_green            // до лимита включительно
                    ratio <= 1.5 -> R.color.yellow                 // от лимита до +50%
                    ratio <= 2.0 -> R.color.orange                 // от +50% до +100%
                    ratio <= 3.0 -> R.color.red                    // от +100% до +200%
                    else -> R.color.dark_burgundy                       // более +200%
                }
            }
        }
    }


    private fun getDayStartMillis(timeMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun loadMonthDataAsync(dateMillis: Long) {
        val calendar = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val currentMonth = calendar.get(Calendar.MONTH)

        // Если данные для этого месяца уже загружены, пропускаем
        if (lastMonthCalculated == currentMonth) return
        lastMonthCalculated = currentMonth

        CoroutineScope(Dispatchers.IO).launch {
            val newEvents = mutableMapOf<Long, List<ExpenseEvent>>()
            val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

            for (day in 1..daysInMonth) {
                calendar.set(Calendar.DAY_OF_MONTH, day)
                val dayStart = getDayStartMillis(calendar.timeInMillis)
                val events = loadEventsForDate(dayStart)
                newEvents[dayStart] = events
            }

            withContext(Dispatchers.Main) {
                cachedEvents = newEvents
                updateCalendarStyles()
                updateFinancialStatusForSelectedDate()
            }
        }
    }


    private fun clearCalendarOnce() {
        if (sharedPref.getBoolean("calendar_cleared", false)) return

        val selection = "${CalendarContract.Events.DESCRIPTION} LIKE ?"
        val selectionArgs = arrayOf("%Обед:%") // Твои события с подписями

        contentResolver.delete(CalendarContract.Events.CONTENT_URI, selection, selectionArgs)

        sharedPref.edit { putBoolean("calendar_cleared", true) }
        showToast("Старые события очищены")
    }

    private fun resetAllData() {
        // 1. Показываем диалог подтверждения
        AlertDialog.Builder(this)
            .setTitle("Полный сброс данных")
            .setMessage("Удалить ВСЕ события календаря, траты и настройки? Это действие нельзя отменить!")
            .setPositiveButton("Удалить") { _, _ ->
                if (checkCalendarPermission()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // 2. Удаляем события календаря (в фоновом потоке)
                            deleteAllCalendarEvents()

                            // 3. Чистим SharedPreferences (оставляя только лимиты по умолчанию)
                            resetSharedPrefs()

                            // 4. Обновляем UI в главном потоке
                            withContext(Dispatchers.Main) {
                                cachedEvents = emptyMap()
                                updateCalendarStyles()
                                updateFinancialStatusForSelectedDate()
                                showToast("Все данные сброшены")
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                showToast("Ошибка: ${e.message}")
                                Log.e("ResetData", "Failed: ${e.stackTraceToString()}")
                            }
                        }
                    }
                } else {
                    showToast("Нужны разрешения для календаря")
                    checkAndRequestPermissions()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Удаляет все события календаря
    @SuppressLint("Range")
    private fun deleteAllCalendarEvents() {
        val eventsUri = CalendarContract.Events.CONTENT_URI
        val cursor = contentResolver.query(
            eventsUri,
            arrayOf(CalendarContract.Events._ID),
            null, null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val eventId = it.getLong(it.getColumnIndex(CalendarContract.Events._ID))
                contentResolver.delete(
                    ContentUris.withAppendedId(eventsUri, eventId),
                    null, null
                )
            }
        }
    }

    // Сбрасывает SharedPreferences, сохраняя лимиты по умолчанию
    private fun resetSharedPrefs() {
        val defaultMin = sharedPref.getFloat("limit_min", 400f) // Сохраняем текущие лимиты
        val defaultAvg = sharedPref.getFloat("limit_avg", 500f)
        val defaultMax = sharedPref.getFloat("limit_max", 700f)
        val beginingLimit = sharedPref.getFloat("limit_total", 0f)

        sharedPref.edit().apply {
            clear()
            putFloat("limit_min", defaultMin) // Восстанавливаем дефолтные лимиты
            putFloat("limit_avg", defaultAvg)
            putFloat("limit_max", defaultMax)
            putFloat("limit_total",beginingLimit)
            putBoolean("calendar_cleared", false) // Сбрасываем флаг очистки
            apply()
        }
    }

    // Проверяем разрешение для календаря
    private fun checkCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
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
        // Проверяем кэш сначала
        val dayStart = getDayStartMillis(dateMillis)
        cachedEvents[dayStart]?.let { return it }

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

        val selection =
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(start.toString(), end.toString())

        val cursor = contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION),
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        cursor?.use { it ->
            while (it.moveToNext()) {
                val title = it.getString(it.getColumnIndex(CalendarContract.Events.TITLE)) ?: ""
                val description =
                    it.getString(it.getColumnIndex(CalendarContract.Events.DESCRIPTION)) ?: ""

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

        // Обновляем кэш
        val newEvents = cachedEvents.toMutableMap().apply {
            put(dayStart, events)
        }
        cachedEvents = newEvents

        return events
    }

    private fun addEventToCalendar(description: String, amount: Double) {
        val calendarId = getCalendarId() ?: return showToast("Не удалось найти календарь")

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, selectedDateMillis)
            put(CalendarContract.Events.DTEND, selectedDateMillis + 60 * 60 * 1000)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        try {
            contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            showToast("Событие добавлено!")

            // Обновляем кэш для текущей даты
            val dayStart = getDayStartMillis(selectedDateMillis)
            val newEvent = ExpenseEvent("Расход", description, amount)
            val currentEvents = cachedEvents[dayStart] ?: emptyList()

            val newEvents = cachedEvents.toMutableMap().apply {
                put(dayStart, currentEvents + newEvent)
            }
            cachedEvents = newEvents

            updateFinancialStatus()
            updateCalendarStyles()
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

    private fun saveCurrentBalance(value: Double) {
        sharedPref.edit { putFloat("current_balance", value.toFloat()) }
    }

    @SuppressLint("MissingInflatedId")
    private fun showBalanceScreen() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_balance_reworked, null)

        val editBalance = dialogView.findViewById<EditText>(R.id.edit_balance)
        val editSalary = dialogView.findViewById<EditText>(R.id.edit_salary)
        val editAdvance = dialogView.findViewById<EditText>(R.id.edit_advance)
        val editZhkh = dialogView.findViewById<EditText>(R.id.edit_zhkh)
        val editMortgage = dialogView.findViewById<EditText>(R.id.edit_mortgage)
        val editCredit = dialogView.findViewById<EditText>(R.id.edit_credit)
        val editOther = dialogView.findViewById<EditText>(R.id.edit_other)

        val textTotal = dialogView.findViewById<TextView>(R.id.text_total_monthly)
        val textRemain = dialogView.findViewById<TextView>(R.id.text_remaining)

        fun updateTotals() {
            val salary = editSalary.text.toString().toDoubleOrNull() ?: 0.0
            val advance = editAdvance.text.toString().toDoubleOrNull() ?: 0.0
            val balance = editBalance.text.toString().toDoubleOrNull() ?: 0.0
            val zhkh = editZhkh.text.toString().toDoubleOrNull() ?: 0.0
            val mortgage = editMortgage.text.toString().toDoubleOrNull() ?: 0.0
            val credit = editCredit.text.toString().toDoubleOrNull() ?: 0.0
            val other = editOther.text.toString().toDoubleOrNull() ?: 0.0

            val expenses = zhkh + mortgage + credit + other
            val free = salary + advance - expenses
            val remain = balance + free

            textTotal.text = "Общий ежемес.расход: %.2f ₽".format(expenses)
            textRemain.text = "Остаток денег на жизнь: %.2f ₽".format(remain)
        }

        listOf(
            editBalance,
            editSalary,
            editAdvance,
            editZhkh,
            editMortgage,
            editCredit,
            editOther
        ).forEach {
            it.addTextChangedListener { _ -> updateTotals() }
        }

        // Подставим старые ПРЕДПОЛАГАЕМЫЕ значения
        editBalance.setText(sharedPref.getFloat("balance_manual", 0f).toString())
        editSalary.setText(sharedPref.getFloat("income_salary_forecast", 0f).toString())
        editAdvance.setText(sharedPref.getFloat("income_advance_forecast", 0f).toString())
        editZhkh.setText(sharedPref.getFloat("monthly_zhkh_forecast", 0f).toString())
        editMortgage.setText(sharedPref.getFloat("monthly_mortgage_forecast", 0f).toString())
        editCredit.setText(sharedPref.getFloat("monthly_credit_forecast", 0f).toString())
        editOther.setText(sharedPref.getFloat("monthly_other_forecast", 0f).toString())

        updateTotals()

        AlertDialog.Builder(this)
            .setTitle("Доход и ежемес.расходы")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val balance = editBalance.text.toString().toDoubleOrNull() ?: 0.0
                val salary = editSalary.text.toString().toDoubleOrNull() ?: 0.0
                val advance = editAdvance.text.toString().toDoubleOrNull() ?: 0.0
                val zhkh = editZhkh.text.toString().toDoubleOrNull() ?: 0.0
                val mortgage = editMortgage.text.toString().toDoubleOrNull() ?: 0.0
                val credit = editCredit.text.toString().toDoubleOrNull() ?: 0.0
                val other = editOther.text.toString().toDoubleOrNull() ?: 0.0

                sharedPref.edit().apply {
                    putFloat("balance_manual", balance.toFloat())
                    putFloat("income_salary_forecast", salary.toFloat())
                    putFloat("income_advance_forecast", advance.toFloat())
                    putFloat("monthly_zhkh_forecast", zhkh.toFloat())
                    putFloat("monthly_mortgage_forecast", mortgage.toFloat())
                    putFloat("monthly_credit_forecast", credit.toFloat())
                    putFloat("monthly_other_forecast", other.toFloat())
                    apply()
                }

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
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.WRITE_CALENDAR)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
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