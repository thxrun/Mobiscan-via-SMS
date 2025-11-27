package com.example.mobiscan_demo2

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*

class SimTrackingService : Service() {

    companion object {
        private const val TAG = "SimTrackingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sim_tracking_channel"
        private const val CHANNEL_NAME = "SIM Tracking"
        private const val TRACKING_DURATION = 300000L // 5 minutes
        private const val LOCATION_UPDATE_INTERVAL = 30000L // 30 seconds
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var database: FirebaseDatabase
    private var trackingJob: Job? = null
    private var locationUpdateJob: Job? = null
    private var isTracking = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        database = FirebaseDatabase.getInstance()
        createNotificationChannel()
        Log.d(TAG, "SimTrackingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SimTrackingService started")
        
        val changeType = intent?.getStringExtra("change_type") ?: "UNKNOWN"
        val alternateNumber = intent?.getStringExtra("alternate_number") ?: ""
        val timestamp = intent?.getStringExtra("timestamp") ?: ""
        val simSerial = intent?.getStringExtra("sim_serial") ?: ""
        val simState = intent?.getIntExtra("sim_state", TelephonyManager.SIM_STATE_UNKNOWN) ?: TelephonyManager.SIM_STATE_UNKNOWN

        startForeground(NOTIFICATION_ID, createNotification(changeType))
        
        if (alternateNumber.isNotBlank()) {
            startTracking(changeType, alternateNumber, timestamp, simSerial, simState)
        } else {
            Log.w(TAG, "No alternate number provided, stopping service")
            stopSelf()
        }

        return START_STICKY
    }

    private fun startTracking(changeType: String, alternateNumber: String, timestamp: String, simSerial: String, simState: Int) {
        if (isTracking) {
            Log.w(TAG, "Tracking already in progress")
            return
        }

        isTracking = true
        Log.i(TAG, "Starting SIM tracking for change: $changeType")

        // Send immediate notification
        sendSimChangeNotification(alternateNumber, changeType, timestamp, simSerial, simState)

        // Start continuous location tracking
        trackingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Track for 5 minutes
                delay(TRACKING_DURATION)
                stopTracking()
            } catch (e: Exception) {
                Log.e(TAG, "Tracking job error: ${e.message}", e)
                stopTracking()
            }
        }

        // Start periodic location updates
        startPeriodicLocationUpdates(alternateNumber, changeType)
    }

    private fun startPeriodicLocationUpdates(alternateNumber: String, changeType: String) {
        locationUpdateJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isTracking) {
                    getCurrentLocation { location ->
                        if (location != null) {
                            sendLocationUpdate(alternateNumber, changeType, location)
                            saveLocationToFirebase(location, changeType)
                        }
                    }
                    delay(LOCATION_UPDATE_INTERVAL)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Location update job error: ${e.message}", e)
            }
        }
    }

    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted")
            callback(null)
            return
        }

        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    callback(location)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Location error: ${exception.message}", exception)
                    callback(null)
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error", e)
            callback(null)
        }
    }

    private fun sendSimChangeNotification(phoneNumber: String, changeType: String, timestamp: String, simSerial: String, simState: Int) {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "SMS permission not granted")
                return
            }

            val simStateText = when (simState) {
                TelephonyManager.SIM_STATE_READY -> "READY"
                TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                else -> "UNKNOWN"
            }

            val message = """
                🚨 SIM CARD $changeType ALERT 🚨
                
                📱 Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
                📅 Time: $timestamp
                🔄 Change: $changeType
                📋 SIM State: $simStateText
                🔢 SIM Serial: ${if (simSerial != "unknown") simSerial.take(8) + "..." else "Unknown"}
                
                📍 Getting location...
            """.trimIndent()

            sendSmsMessage(phoneNumber, message)
            Log.i(TAG, "SIM change notification sent to $phoneNumber")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SIM change notification: ${e.message}", e)
        }
    }

    private fun sendLocationUpdate(phoneNumber: String, changeType: String, location: Location) {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "SMS permission not granted")
                return
            }

            val timestamp = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date())
            val accuracy = location.accuracy

            val message = """
                📍 LIVE LOCATION UPDATE
                
                🚨 SIM $changeType detected
                📱 Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
                📅 Time: $timestamp
                📍 Coordinates: ${location.latitude}, ${location.longitude}
                🎯 Accuracy: ${accuracy}m
                🗺️ Map: https://maps.google.com/?q=${location.latitude},${location.longitude}
                
                🔄 Tracking active...
            """.trimIndent()

            sendSmsMessage(phoneNumber, message)
            Log.i(TAG, "Location update sent to $phoneNumber")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send location update: ${e.message}", e)
        }
    }

    private fun sendSmsMessage(phoneNumber: String, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            val parts = smsManager.divideMessage(message)
            
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS: ${e.message}", e)
        }
    }

    private fun saveLocationToFirebase(location: Location, changeType: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date())
            val locationData = mapOf(
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "accuracy" to location.accuracy,
                "timestamp" to timestamp,
                "change_type" to changeType,
                "device_model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )

            database.reference.child("sim_tracking").child(timestamp.replace(":", "_").replace("/", "_")).setValue(locationData)
                .addOnSuccessListener {
                    Log.d(TAG, "Location saved to Firebase")
                }
                .addOnFailureListener { exception ->
                    Log.w(TAG, "Failed to save location to Firebase: ${exception.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase save error: ${e.message}", e)
        }
    }

    private fun stopTracking() {
        isTracking = false
        trackingJob?.cancel()
        locationUpdateJob?.cancel()
        
        Log.i(TAG, "SIM tracking stopped")
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SIM tracking notifications"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(changeType: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚨 SIM Tracking Active")
            .setContentText("Monitoring location after SIM $changeType")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        Log.d(TAG, "SimTrackingService destroyed")
    }
}
