package com.ivac.otpforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern

class SmsOtpReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsOtpReceiver"
        private const val DEFAULT_FORWARD_URL = "https://ais-dev-r6fwghsme2uhic2o5q6fsu-466272014554.asia-southeast1.run.app/api/otp/push"
        private val httpClient = OkHttpClient()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val fullBody = StringBuilder()
            var sender = ""

            for (sms in messages) {
                fullBody.append(sms.messageBody)
                sender = sms.displayOriginatingAddress ?: ""
            }

            val smsText = fullBody.toString()
            Log.d(TAG, "Incoming SMS from: $sender -> $smsText")

            val subId = intent.extras?.getInt("subscription", -1) ?: -1
            val simSlotIndex = intent.extras?.getInt("slot", -1) ?: -1

            val simSlotNumber = if (simSlotIndex >= 0) {
                simSlotIndex + 1
            } else {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val subInfo = subManager?.activeSubscriptionInfoList?.find { it.subscriptionId == subId }
                (subInfo?.simSlotIndex ?: 0) + 1
            }

            val prefs = context.getSharedPreferences("ivac_otp_prefs", Context.MODE_PRIVATE)
            val phoneSim1 = prefs.getString("sim1_number", "") ?: ""
            val phoneSim2 = prefs.getString("sim2_number", "") ?: ""
            val customServerUrl = prefs.getString("server_url", DEFAULT_FORWARD_URL) ?: DEFAULT_FORWARD_URL

            val targetPhoneNumber = if (simSlotNumber == 2 && phoneSim2.isNotBlank()) {
                phoneSim2
            } else if (simSlotNumber == 1 && phoneSim1.isNotBlank()) {
                phoneSim1
            } else {
                phoneSim1.ifBlank { phoneSim2 }
            }

            val otpCode = extractOtpCode(smsText)

            if (!otpCode.isNullOrBlank() && targetPhoneNumber.isNotBlank()) {
                Log.d(TAG, "Dispatched OTP [$otpCode] from SIM $simSlotNumber to $targetPhoneNumber")
                forwardOtpToServer(
                    serverUrl = customServerUrl,
                    phoneNumber = targetPhoneNumber,
                    otpCode = otpCode,
                    simSlot = simSlotNumber,
                    simName = if (simSlotNumber == 2) "SIM 2" else "SIM 1",
                    rawSender = sender,
                    rawText = smsText
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming SMS", e)
        }
    }

    private fun extractOtpCode(message: String): String? {
        val pattern6 = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)")
        val matcher6 = pattern6.matcher(message)
        if (matcher6.find()) {
            return matcher6.group(1)
        }

        val pattern4 = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)")
        val matcher4 = pattern4.matcher(message)
        if (matcher4.find()) {
            return matcher4.group(1)
        }

        return null
    }

    private fun forwardOtpToServer(
        serverUrl: String,
        phoneNumber: String,
        otpCode: String,
        simSlot: Int,
        simName: String,
        rawSender: String,
        rawText: String
    ) {
        val jsonPayload = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("otpCode", otpCode)
            put("simSlot", simSlot)
            put("simName", simName)
            put("rawSender", rawSender)
            put("rawText", rawText)
        }

        val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(serverUrl)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to push OTP to server", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        Log.i(TAG, "OTP successfully routed to server: " + response.code)
                    } else {
                        Log.w(TAG, "Server returned error: " + response.code)
                    }
                }
            }
        })
    }
}
