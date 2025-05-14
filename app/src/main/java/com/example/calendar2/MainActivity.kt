package com.example.calendar2

import android.Manifest
import android.annotation.SuppressLint
import com.google.gson.Gson
import androidx.recyclerview.widget.LinearLayoutManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.calendar2.databinding.ActivityMainBinding
import com.google.firebase.crashlytics.buildtools.reloc.com.google.common.reflect.TypeToken
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val requestcode = 100
    private var selectedDateMillis: Long = 0L
    private val sharedPref by lazy { getSharedPreferences("FinancePrefs", MODE_PRIVATE) }
    private val subscriptions = mutableListOf<Subscription>()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()
        loadSubscriptions()

        setupViews()
    }

    private fun setupViews() {
        binding.apply {
            addExpenses.setOnClickListener {
                if (selectedDateMillis == 0L) {
                    showToast("Сначала выберите дату на календаре")
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

            balanceButton.setOnClickListener {
                showBalanceScreen()
            }

            subscriptionsButton.setOnClickListener {
                showSubscriptionsDialog()
            }
        }
    }

    private fun showAddEventDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_expenses, null)

        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radioGroup)
        val editTransport = dialogView.findViewById<EditText>(R.id.edit_transport)
        val editShop = dialogView.findViewById<EditText>(R.id.edit_shop)
        val editEntertainment = dialogView.findViewById<EditText>(R.id.edit_entertainment)
        val editOther = dialogView.findViewById<EditText>(R.id.edit_other)

        AlertDialog.Builder(this)
            .setTitle("Добавить событие")
            .setView(dialogView)
            .setPositiveButton("Добавить") { _, _ ->
                val selectedRadioId = radioGroup.checkedRadioButtonId
                val radioText = dialogView.findViewById<RadioButton>(selectedRadioId)?.text ?: "Не выбран"

                val transportAmount = editTransport.text.toString().toDoubleOrNull() ?: 0.0
                val shopAmount = editShop.text.toString().toDoubleOrNull() ?: 0.0
                val entertainmentAmount = editEntertainment.text.toString().toDoubleOrNull() ?: 0.0
                val otherAmount = editOther.text.toString().toDoubleOrNull() ?: 0.0

                val totalAmount = transportAmount + shopAmount + entertainmentAmount + otherAmount

                val description = buildString {
                    append("Обед: $radioText (${radioText.toString().substringAfterLast(" ")})\n")
                    append("Транспорт: $transportAmount₽\n")
                    append("Магазин: $shopAmount₽\n")
                    append("Развлечения: $entertainmentAmount₽\n")
                    append("Другое: $otherAmount₽\n")
                    append("Итого: $totalAmount₽")
                }

                deleteEventsForDate(selectedDateMillis)
                addEventToCalendar("Расходы", description)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateFinancialStatus() {
        val events = loadEventsForDate(selectedDateMillis)
        val dailyExpenses = calculateDailyExpenses(events)
        val monthlyExpenses = calculateMonthlyExpenses(selectedDateMillis)
        val totalExpenses = dailyExpenses + monthlyExpenses
        val currentBalance = getCurrentBalance()
        val projectedBalance = currentBalance - totalExpenses

        binding.financialStatus.text = buildString {
            append("Траты за день: ${"%.2f".format(dailyExpenses)}₽\n")
            append("Подписки: ${"%.2f".format(monthlyExpenses)}₽\n")
            append("Итого расходов: ${"%.2f".format(totalExpenses)}₽\n")
            append("Текущий баланс: ${"%.2f".format(currentBalance)}₽\n")
            append("Прогноз на конец дня: ${"%.2f".format(projectedBalance)}₽")
        }

        // Обновляем отображение событий
        if (events.isNotEmpty()) {
            binding.eventsTextView.text = events.joinToString("\n\n") {
                "${it.title}\n${it.description}"
            }
        } else {
            binding.eventsTextView.text = "Событий на выбранную дату нет."
        }
    }

    private fun showBalanceScreen() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_balance, null)
        val currentBalance = getCurrentBalance()
        val editBalance = dialogView.findViewById<EditText>(R.id.edit_balance).apply {
            setText(currentBalance.toString())
        }

        AlertDialog.Builder(this)
            .setTitle("Управление балансом")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val newBalance = editBalance.text.toString().toDoubleOrNull() ?: 0.0
                saveCurrentBalance(newBalance)
                updateFinancialStatus()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showSubscriptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_subscriptions, null)
        val subscriptionsList = dialogView.findViewById<RecyclerView>(R.id.subscriptions_list)
        val editName = dialogView.findViewById<EditText>(R.id.edit_subscription_name)
        val editAmount = dialogView.findViewById<EditText>(R.id.edit_subscription_amount)
        val addButton = dialogView.findViewById<Button>(R.id.add_subscription_button)

        val adapter = SubscriptionsAdapter(subscriptions) { subscription ->
            subscriptions.remove(subscription)
            saveSubscriptions()

        }

        adapter.notifyDataSetChanged()

        subscriptionsList.layoutManager = LinearLayoutManager(this)
        subscriptionsList.adapter = adapter

        addButton.setOnClickListener {
            val name = editName.text.toString()
            val amount = editAmount.text.toString().toDoubleOrNull() ?: 0.0

            if (name.isNotBlank() && amount > 0) {
                subscriptions.add(Subscription(name, amount))
                saveSubscriptions()
                adapter.notifyDataSetChanged()
                editName.text.clear()
                editAmount.text.clear()
            } else {
                showToast("Введите название и сумму подписки")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Управление подписками")
            .setView(dialogView)
            .setPositiveButton("Готово") { _, _ ->
                updateFinancialStatus()
            }
            .show()
    }

    private fun calculateDailyExpenses(events: List<Event>): Double {
        return events.sumOf { it.amount }
    }

    private fun calculateMonthlyExpenses(dateMillis: Long): Double {
        val calendar = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        return subscriptions.sumOf { subscription ->
            subscription.amount * dayOfMonth / daysInMonth
        }
    }

    private fun getCurrentBalance(): Double {
        return sharedPref.getFloat("current_balance", 0f).toDouble()
    }

    private fun saveCurrentBalance(balance: Double) {
        sharedPref.edit().putFloat("current_balance", balance.toFloat()).apply()
    }

    private fun loadSubscriptions() {
        val json = sharedPref.getString("subscriptions", "[]") ?: "[]"
        val type = object : TypeToken<List<Subscription>>() {}.type
        subscriptions.addAll(gson.fromJson(json, type))
    }

    private fun saveSubscriptions() {
        val json = Gson().toJson(subscriptions)
        sharedPref.edit().putString("subscriptions", json).apply()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_CALENDAR)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CALENDAR)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), requestcode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestcode && grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Нужны разрешения для работы с календарем", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("Range")
    private fun loadEventsForDate(dateMillis: Long): List<Event> {
        val events = mutableListOf<Event>()
        val start = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val end = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(start.timeInMillis.toString(), end.timeInMillis.toString())

        val cursor = contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val title = it.getString(it.getColumnIndex(CalendarContract.Events.TITLE))
                val description = it.getString(it.getColumnIndex(CalendarContract.Events.DESCRIPTION))

                // Парсим сумму из описания (предполагаем формат "Категория: 100.0₽")
                val amount = try {
                    description?.substringAfterLast(" ")?.replace("₽", "")?.toDoubleOrNull() ?: 0.0
                } catch (e: Exception) {
                    0.0
                }

                events.add(Event(title ?: "", description ?: "", amount))
            }
        }

        return events
    }

    @SuppressLint("Range")
    private fun getCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val cursor = contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(it.getColumnIndex(CalendarContract.Calendars._ID))
            }
        }
        return null
    }

    private fun addEventToCalendar(title: String, description: String) {
        val calendarId = getCalendarId()
        if (calendarId == null) {
            Toast.makeText(this, "Не удалось найти календарь", Toast.LENGTH_SHORT).show()
            return
        }

        val endMillis = selectedDateMillis + 60 * 60 * 1000

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, selectedDateMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        try {
            contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            Toast.makeText(this, "Событие добавлено!", Toast.LENGTH_SHORT).show()
            updateFinancialStatus() // Обновляем весь финансовый статус
        } catch (e: Exception) {
            Log.e("CalendarError", "Ошибка: ${e.message}", e)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    private fun deleteEventsForDate(dateMillis: Long) {
        val start = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val end = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(start.timeInMillis.toString(), end.timeInMillis.toString())

        val rowsDeleted = contentResolver.delete(CalendarContract.Events.CONTENT_URI, selection, selectionArgs)
        Log.d("CalendarDelete", "Удалено событий: $rowsDeleted")
    }
}

// Новые классы данных
data class Subscription(val name: String, val amount: Double)
data class Event(val title: String, val description: String, val amount: Double)

// Адаптер для RecyclerView
class SubscriptionsAdapter(
    private val subscriptions: List<Subscription>,
    private val onDelete: (Subscription) -> Unit
) : RecyclerView.Adapter<SubscriptionsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.subscription_name)
        val amount: TextView = view.findViewById(R.id.subscription_amount)
        val delete: ImageButton = view.findViewById(R.id.delete_subscription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subscription, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val subscription = subscriptions[position]
        holder.name.text = subscription.name
        holder.amount.text = "${subscription.amount}₽"
        holder.delete.setOnClickListener { onDelete(subscription) }
    }

    override fun getItemCount() = subscriptions.size
}
