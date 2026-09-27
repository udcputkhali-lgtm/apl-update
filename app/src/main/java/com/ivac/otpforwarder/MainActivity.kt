package com.ivac.otpforwarder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etSim1Phone: EditText
    private lateinit var etSim2Phone: EditText
    private lateinit var tvSim1Status: TextView
    private lateinit var tvSim2Status: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var btnRequestBattery: Button
    private lateinit var llActivityContainer: LinearLayout
    private lateinit var tvNoActivityPlaceholder: TextView
    private lateinit var tvLiveIndicator: TextView

    private val httpClient = OkHttpClient()
    private var pollTimer: Timer? = null

    companion object {
        private const val PERMISSION_REQ_CODE = 1001
        private const val SERVER_BASE_URL = "https://ais-dev-r6fwghsme2uhic2o5q6fsu-466272014554.asia-southeast1.run.app"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ivac_otp_prefs", Context.MODE_PRIVATE)

        etSim1Phone = findViewById(R.id.etSim1Phone)
        etSim2Phone = findViewById(R.id.etSim2Phone)
        tvSim1Status = findViewById(R.id.tvSim1Status)
        tvSim2Status = findViewById(R.id.tvSim2Status)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnSave = findViewById(R.id.btnSave)
        btnRequestBattery = findViewById(R.id.btnRequestBattery)
        llActivityContainer = findViewById(R.id.llActivityContainer)
        tvNoActivityPlaceholder = findViewById(R.id.tvNoActivityPlaceholder)
        tvLiveIndicator = findViewById(R.id.tvLiveIndicator)

        etSim1Phone.setText(prefs.getString("sim1_number", ""))
        etSim2Phone.setText(prefs.getString("sim2_number", ""))

        btnSave.setOnClickListener {
            val s1 = etSim1Phone.text.toString().trim()
            val s2 = etSim2Phone.text.toString().trim()

            prefs.edit()
                .putString("sim1_number", s1)
                .putString("sim2_number", s2)
                .apply()

            Toast.makeText(this, "সিম নম্বর সফলভাবে সংরক্ষণ করা হয়েছে!", Toast.LENGTH_SHORT).show()
            fetchRecentActivities()
        }

        btnRequestBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        checkAndRequestPermissions()
        detectSimSlots()
        startForwarderService()
        startActivityPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollTimer?.cancel()
    }

    private fun startActivityPolling() {
        pollTimer?.cancel()
        pollTimer = Timer()
        pollTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                fetchRecentActivities()
            }
        }, 1000, 3500)
    }

    private fun fetchRecentActivities() {
        val s1 = prefs.getString("sim1_number", "") ?: ""
        val s2 = prefs.getString("sim2_number", "") ?: ""
        val url = "$SERVER_BASE_URL/api/activity/history"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvLiveIndicator.text = "🟡 RECONNECTING"
                    tvLiveIndicator.setTextColor(android.graphics.Color.parseColor("#FBBF24"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return
                        try {
                            val json = JSONObject(body)
                            val array = json.getJSONArray("activities")
                            runOnUiThread {
                                renderActivities(array)
                                tvLiveIndicator.text = "🟢 LIVE"
                                tvLiveIndicator.setTextColor(android.graphics.Color.parseColor("#34D399"))
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }
        })
    }

    private fun renderActivities(activities: org.json.JSONArray) {
        if (activities.length() == 0) {
            tvNoActivityPlaceholder.visibility = android.view.View.VISIBLE
            return
        }
        tvNoActivityPlaceholder.visibility = android.view.View.GONE
        llActivityContainer.removeAllViews()

        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())

        for (i in 0 until minOf(activities.length(), 6)) {
            val item = activities.getJSONObject(i)
            val type = item.optString("type", "")
            val title = item.optString("title", "")
            val message = item.optString("message", "")
            val phone = item.optString("phoneNumber", "")
            val ts = item.optLong("timestamp", System.currentTimeMillis())
            val timeStr = timeFormat.format(Date(ts))

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 20, 28, 20)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 16)
                layoutParams = params

                val (bgColor, strokeColor, titleColor) = when (type) {
                    "LOGIN_SUCCESS" -> Triple("#064E3B", "#059669", "#34D399")
                    "FILE_UPLOADED" -> Triple("#1E1B4B", "#4338CA", "#818CF8")
                    "SLOT_RELEASED" -> Triple("#065F46", "#10B981", "#6EE7B7")
                    "PAYMENT_INITIATED" -> Triple("#7C2D12", "#EA580C", "#FDBA74")
                    "OTP_RECEIVED" -> Triple("#1E293B", "#3B82F6", "#93C5FD")
                    else -> Triple("#0F172A", "#334155", "#E2E8F0")
                }

                val shape = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor(bgColor))
                    cornerRadius = 16f
                    setStroke(2, android.graphics.Color.parseColor(strokeColor))
                }
                background = shape
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvTitle = TextView(this).apply {
                text = title
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val tColor = when (type) {
                    "LOGIN_SUCCESS" -> "#34D399"
                    "FILE_UPLOADED" -> "#A5B4FC"
                    "SLOT_RELEASED" -> "#6EE7B7"
                    "PAYMENT_INITIATED" -> "#FDBA74"
                    else -> "#93C5FD"
                }
                setTextColor(android.graphics.Color.parseColor(tColor))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTime = TextView(this).apply {
                text = timeStr
                textSize = 10f
                setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }

            headerRow.addView(tvTitle)
            headerRow.addView(tvTime)
            card.addView(headerRow)

            val tvMsg = TextView(this).apply {
                text = message + (if (phone.isNotBlank()) " • $phone" else "")
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#CBD5E1"))
                setPadding(0, 8, 0, 0)
            }
            card.addView(tvMsg)

            llActivityContainer.addView(card)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), PERMISSION_REQ_CODE)
        }
    }

    private fun detectSimSlots() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subList: List<SubscriptionInfo>? = subManager.activeSubscriptionInfoList

            if (!subList.isNullOrEmpty()) {
                for (info in subList) {
                    val slot = info.simSlotIndex + 1
                    val carrier = info.carrierName ?: "Carrier"
                    if (slot == 1) {
                        tvSim1Status.text = "📶 সিম ১ সক্রিয় ($carrier)"
                    } else if (slot == 2) {
                        tvSim2Status.text = "📶 সিম ২ সক্রিয় ($carrier)"
                    }
                }
            }
        } catch (e: SecurityException) {
            // handle
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "ব্যাটারি অপ্টিমাইজেশন ইতিমধ্যে বন্ধ রয়েছে (Unrestricted)!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startForwarderService() {
        val serviceIntent = Intent(this, OtpForwarderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        tvServiceStatus.text = "সার্ভিস স্ট্যাটাস: সার্বক্ষণিক সক্রিয় (Active)"
    }
}
