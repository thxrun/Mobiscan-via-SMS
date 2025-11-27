package com.example.mobiscan_demo2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.TelephonyManager
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import androidx.core.app.ActivityCompat
import android.app.admin.DevicePolicyManager
import android.content.ComponentName


class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MobileFinderSMS"

        // Command categories with specific keywords
        private val RING_KEYWORDS = arrayOf(
            "MOBI RING", "MOBI BUZZ", "MOBI SOUND", "MOBI ALARM", "MOBI FINDME"
        )

        private val LOCATION_KEYWORDS = arrayOf(
            "MOBI LOCATION", "MOBI WHERE", "MOBI LOCATE", "MOBI GPS", "MOBI TRACK"
        )

        private val LOCK_KEYWORDS = arrayOf(
            "MOBI LOCK", "MOBI SECURE", "MOBI PROTECT", "MOBI EMERGENCY"
        )

        private val HELP_KEYWORDS = arrayOf(
            "MOBI HELP", "MOBI COMMANDS", "MOBI INFO"
        )

        private val SIM_TRACKING_KEYWORDS = arrayOf(
            "MOBI SIM", "MOBI SIMSTATUS", "MOBI SIMINFO", "MOBI SIMCHECK"
        )

        // Command types
        enum class CommandType {
            RING, LOCATION, LOCK, HELP, SIM_TRACKING, UNKNOWN
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Only process SMS_RECEIVED action
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") {
            return
        }

        try {
            processIncomingSms(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS: ${e.message}", e)
        }
    }

    private fun processIncomingSms(context: Context, intent: Intent) {
        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return

        Log.d(TAG, "Processing ${pdus.size} SMS PDU(s)")

        for (pdu in pdus) {
            try {
                val smsMessage = createSmsMessage(pdu as ByteArray, bundle.getString("format"))
                if (smsMessage != null) {
                    handleSmsMessage(context, smsMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS PDU: ${e.message}", e)
            }
        }
    }

    private fun createSmsMessage(pdu: ByteArray, format: String?): SmsMessage? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu, format)
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SMS message: ${e.message}", e)
            null
        }
    }

    private fun handleSmsMessage(context: Context, smsMessage: SmsMessage) {
        val messageBody = smsMessage.messageBody?.trim() ?: ""
        val senderNumber = smsMessage.originatingAddress ?: "Unknown"

        Log.d(TAG, "SMS from $senderNumber: '$messageBody'")

        if (messageBody.isEmpty()) {
            Log.d(TAG, "Empty message body, ignoring")
            return
        }

        val commandType = detectCommandType(messageBody)
        val foundTrigger = getMatchedKeyword(messageBody, commandType)

        if (commandType != CommandType.UNKNOWN && foundTrigger != null) {
            Log.i(TAG, "Command '$commandType' detected with trigger '$foundTrigger'")
            executeCommand(context, senderNumber, commandType, foundTrigger)
        } else {
            Log.d(TAG, "No valid command found in message")
        }
    }

    private fun detectCommandType(messageBody: String): CommandType {
        val messageUpperCase = messageBody.uppercase()

        return when {
            RING_KEYWORDS.any { messageUpperCase.contains(it) } -> CommandType.RING
            LOCATION_KEYWORDS.any { messageUpperCase.contains(it) } -> CommandType.LOCATION
            LOCK_KEYWORDS.any { messageUpperCase.contains(it) } -> CommandType.LOCK
            HELP_KEYWORDS.any { messageUpperCase.contains(it) } -> CommandType.HELP
            SIM_TRACKING_KEYWORDS.any { messageUpperCase.contains(it) } -> CommandType.SIM_TRACKING
            else -> CommandType.UNKNOWN
        }
    }

    private fun getMatchedKeyword(messageBody: String, commandType: CommandType): String? {
        val messageUpperCase = messageBody.uppercase()

        return when (commandType) {
            CommandType.RING -> RING_KEYWORDS.find { messageUpperCase.contains(it) }
            CommandType.LOCATION -> LOCATION_KEYWORDS.find { messageUpperCase.contains(it) }
            CommandType.LOCK -> LOCK_KEYWORDS.find { messageUpperCase.contains(it) }
            CommandType.HELP -> HELP_KEYWORDS.find { messageUpperCase.contains(it) }
            CommandType.SIM_TRACKING -> SIM_TRACKING_KEYWORDS.find { messageUpperCase.contains(it) }
            CommandType.UNKNOWN -> null
        }
    }

    private fun executeCommand(context: Context, senderNumber: String, commandType: CommandType, triggerWord: String) {
        try {
            when (commandType) {
                CommandType.RING -> {
                    Log.i(TAG, "Executing RING command")
                    startRingService(context, senderNumber, triggerWord)
                    sendCommandConfirmation(context, senderNumber, "🔊 Ring activated! Your device is now ringing.")
                }

                CommandType.LOCATION -> {
                    Log.i(TAG, "Executing LOCATION command")
                    handleLocationRequest(context, senderNumber, triggerWord)
                }

                CommandType.LOCK -> {
                    Log.i(TAG, "Executing LOCK command")
                    handleDeviceLock(context, senderNumber, triggerWord)
                }

                CommandType.HELP -> {
                    Log.i(TAG, "Executing HELP command")
                    sendHelpResponse(context, senderNumber)
                }

                CommandType.SIM_TRACKING -> {
                    Log.i(TAG, "Executing SIM_TRACKING command")
                    handleSimStatusRequest(context, senderNumber, triggerWord)
                }

                CommandType.UNKNOWN -> {
                    Log.w(TAG, "Unknown command type")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command $commandType: ${e.message}", e)
            sendCommandConfirmation(context, senderNumber, "❌ Command failed. Please try again.")
        }
    }

    private fun startRingService(context: Context, senderNumber: String, triggerWord: String) {
        // Start existing PhoneFinderService for ring functionality
        val serviceIntent = Intent(context, PhoneFinderService::class.java).apply {
            putExtra("command_type", "RING")
            putExtra("sender_number", senderNumber)
            putExtra("trigger_word", triggerWord)
            putExtra("trigger_time", System.currentTimeMillis())
        }
        startServiceSafely(context, serviceIntent)
    }

    private fun handleLocationRequest(context: Context, senderNumber: String, triggerWord: String) {
        try {
            // Check location permissions
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Location permission not granted")
                sendCommandConfirmation(context, senderNumber, "❌ Location permission not available. Please enable location access.")
                return
            }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Try to get last known location
            var bestLocation: Location? = null

            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    bestLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "GPS provider access denied")
            }

            try {
                if (bestLocation == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    bestLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Network provider access denied")
            }

            if (bestLocation != null) {
                Log.i(TAG, "Location found: ${bestLocation.latitude}, ${bestLocation.longitude}")
                sendLocationResponse(context, senderNumber, bestLocation.latitude, bestLocation.longitude)
            } else {
                Log.w(TAG, "No location available")
                sendCommandConfirmation(context, senderNumber, "❌ Location not available. Please turn on GPS and try again.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting location: ${e.message}", e)
            sendCommandConfirmation(context, senderNumber, "❌ Failed to get location. Please try again.")
        }
    }

    private fun handleDeviceLock(context: Context, senderNumber: String, triggerWord: String) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)

            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                sendCommandConfirmation(context, senderNumber, "🔒 Device locked remotely via SMS.")
                Log.i(TAG, "Device locked remotely")
            } else {
                sendCommandConfirmation(context, senderNumber, "❌ Lock failed. Device Admin not enabled.\nOpen the app and tap 'Lock Device' to enable it.")
                Log.w(TAG, "Device Admin not active; cannot lock")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling device lock: ${e.message}", e)
            sendCommandConfirmation(context, senderNumber, "❌ Unable to lock device. Please check permissions.")
        }
    }



    private fun startServiceSafely(context: Context, serviceIntent: Intent) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}", e)
        }
    }

    private fun sendCommandConfirmation(context: Context, phoneNumber: String, message: String) {
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No SMS send permission, cannot send confirmation")
                return
            }

            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            val fullMessage = "📱 Mobile Finder:\n$message"

            val parts = smsManager.divideMessage(fullMessage)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, fullMessage, null, null)
            }

            Log.i(TAG, "Confirmation sent to $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send confirmation: ${e.message}", e)
        }
    }

    private fun sendHelpResponse(context: Context, phoneNumber: String) {
        val helpMessage = """
            📱 Mobile Finder Commands:
            
            🔊 RING: MOBI RING, MOBI BUZZ, MOBI SOUND, MOBI ALARM, MOBI FINDME
            📍 LOCATION: MOBI LOCATION, MOBI WHERE, MOBI LOCATE, MOBI GPS, MOBI TRACK  
            🔒 LOCK: MOBI LOCK, MOBI SECURE, MOBI PROTECT, MOBI EMERGENCY
            📱 SIM: MOBI SIM, MOBI SIMSTATUS, MOBI SIMINFO, MOBI SIMCHECK
            ❓ HELP: MOBI HELP, MOBI COMMANDS, MOBI INFO
            
            Send any command via SMS to activate.
        """.trimIndent()

        sendCommandConfirmation(context, phoneNumber, helpMessage)
    }

    private fun handleSimStatusRequest(context: Context, senderNumber: String, triggerWord: String) {
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_PHONE_STATE permission not granted")
                sendCommandConfirmation(context, senderNumber, "❌ Phone state permission not available. Please enable phone access.")
                return
            }

            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val simState = telephonyManager.simState
            val simSerial = telephonyManager.simSerialNumber ?: "Unknown"
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())

            val simStateText = when (simState) {
                TelephonyManager.SIM_STATE_READY -> "READY"
                TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                else -> "UNKNOWN"
            }

            val simStatusMessage = """
                📱 SIM Card Status:
                
                📋 State: $simStateText
                🔢 Serial: ${simSerial.take(8)}...
                📅 Time: $timestamp
                📱 Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
                
                ${if (simState == TelephonyManager.SIM_STATE_ABSENT) "⚠️ SIM card is not present!" else "✅ SIM card is present and ready."}
            """.trimIndent()

            sendCommandConfirmation(context, senderNumber, simStatusMessage)
            Log.i(TAG, "SIM status sent to $senderNumber")

        } catch (e: Exception) {
            Log.e(TAG, "Error getting SIM status: ${e.message}", e)
            sendCommandConfirmation(context, senderNumber, "❌ Failed to get SIM status. Please try again.")
        }
    }

    private fun sendLocationResponse(context: Context, phoneNumber: String, latitude: Double, longitude: Double) {
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No SMS send permission, cannot reply with location")
                return
            }

            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            val locationMessage = """
                📱 Mobile Finder - Location Found:
                📍 Coordinates: $latitude, $longitude
                🗺️ Map: https://maps.google.com/?q=$latitude,$longitude
                ⏰ Time: ${java.text.SimpleDateFormat("HH:mm:ss dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())}
            """.trimIndent()

            val parts = smsManager.divideMessage(locationMessage)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, locationMessage, null, null)
            }

            Log.i(TAG, "Location response sent to $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send location response: ${e.message}", e)
        }
    }
}