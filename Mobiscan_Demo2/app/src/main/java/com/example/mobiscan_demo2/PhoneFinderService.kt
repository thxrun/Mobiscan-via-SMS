package com.example.mobiscan_demo2

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.SettingsClient
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.OnTokenCanceledListener
import android.location.LocationManager

class PhoneFinderService : Service() {

    companion object {
        private const val TAG = "PhoneFinderService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "PhoneFinderChannel"
        private const val FINDER_DURATION = 45000L // 45 seconds
        private const val LOCATION_TIMEOUT = 8000L // 8 seconds
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var isRinging = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var senderNumber: String? = null
    private var autoStopHandler: Handler? = null
    private var autoStopRunnable: Runnable? = null
    private var locationCallback: LocationCallback? = null
    private var locationTimeoutHandler: Handler? = null
    private var locationTimeoutRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        autoStopHandler = Handler(Looper.getMainLooper())
        locationTimeoutHandler = Handler(Looper.getMainLooper())

        // Initialize vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        Log.d(TAG, "PhoneFinderService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "PhoneFinderService onStartCommand with action: $action")

        // Handle stop action
        if (action == "STOP_FINDER") {
            Log.i(TAG, "Stop finder action received")
            stopPhoneFinder()
            stopSelf()
            return START_NOT_STICKY
        }

        // Get SMS trigger info
        senderNumber = intent?.getStringExtra("sender_number")
        val triggerWord = intent?.getStringExtra("trigger_word") ?: "Manual"

        Log.i(TAG, "Starting phone finder for sender: $senderNumber, trigger: $triggerWord")

        // Start foreground service with notification
        val notification = createNotification("📱 Phone Finder Active", "Starting phone finder...")
        startForeground(NOTIFICATION_ID, notification)

        // Start the phone finder
        startPhoneFinder()

        // Get and send location if SMS triggered
        if (senderNumber != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                getCurrentLocationAndSend()
            }, 2000) // 2 second delay to ensure service is fully started
        }

        // Set auto-stop timer
        autoStopRunnable = Runnable {
            if (isRinging) {
                Log.i(TAG, "Auto-stopping phone finder after 45 seconds")
                stopPhoneFinder()
                stopSelf()
            }
        }
        autoStopHandler?.postDelayed(autoStopRunnable!!, FINDER_DURATION)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone Finder Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows when phone finder is active"
                setShowBadge(false)
                enableVibration(false) // Disable notification vibration to avoid conflict
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        // Create intent to open MainActivity when notification is tapped
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create stop intent
        val stopIntent = Intent(this, PhoneFinderService::class.java).apply {
            action = "STOP_FINDER"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun startPhoneFinder() {
        if (isRinging) {
            Log.w(TAG, "Phone finder already active")
            return
        }

        isRinging = true
        Log.i(TAG, "Starting phone finder - sound and vibration")

        startAlarmSound()
        startVibrationPattern()

        // Update notification
        updateNotification("🔊 PHONE RINGING", "Tap to open app • Auto-stop in 45s")
    }

    private fun startAlarmSound() {
        try {
            stopAlarmSound() // Stop any existing sound

            // Try alarm sound first, then ringtone as fallback
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            if (alarmUri != null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@PhoneFinderService, alarmUri)
                    isLooping = true

                    // Use alarm stream for maximum volume
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                                .build()
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setAudioStreamType(AudioManager.STREAM_ALARM)
                    }

                    prepare()
                    start()
                }

                // Maximize volume
                setMaximumVolume()
                Log.d(TAG, "Alarm sound started successfully")
            } else {
                Log.w(TAG, "No alarm sound URI available")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Sound error: ${e.message}", e)
        }
    }

    private fun setMaximumVolume() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            Log.d(TAG, "Volume set to maximum: $maxVolume")
        } catch (e: Exception) {
            Log.w(TAG, "Volume control failed: ${e.message}")
        }
    }

    private fun startVibrationPattern() {
        try {
            if (vibrator?.hasVibrator() == true) {
                // Strong vibration pattern: [delay, vibrate, pause, vibrate, pause, ...]
                val pattern = longArrayOf(0, 1000, 300, 1000, 300, 1000, 500)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(pattern, 0) // 0 = repeat from start
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0) // 0 = repeat from start
                }
                Log.d(TAG, "Vibration started successfully")
            } else {
                Log.w(TAG, "No vibrator available")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun getCurrentLocationAndSend() {
        if (senderNumber == null) {
            Log.w(TAG, "No sender number, skipping location response")
            return
        }

        Log.d(TAG, "Getting current location for sender: $senderNumber")

        // Check location permission first
        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission, sending simple response")
            sendSimpleResponse()
            return
        }

        // Check if location services are enabled
        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services disabled, sending simple response")
            sendSimpleResponse()
            return
        }

        // Try multiple approaches to get location
        getLocationMultipleAttempts()
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun getLocationMultipleAttempts() {
        Log.d(TAG, "Attempting to get location using multiple methods")

        // Method 1: Try last known location first
        getLastKnownLocationImproved { success ->
            if (!success) {
                Log.d(TAG, "Last known location failed, trying current location")
                // Method 2: Try current location with high accuracy
                getCurrentLocationImproved { success2 ->
                    if (!success2) {
                        Log.d(TAG, "Current location failed, trying location updates")
                        // Method 3: Use location updates as last resort
                        requestLocationUpdates()
                    }
                }
            }
        }
    }

    private fun getLastKnownLocationImproved(onComplete: (Boolean) -> Unit) {
        try {
            if (!hasLocationPermission()) {
                onComplete(false)
                return
            }

            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null && isLocationRecent(location)) {
                        Log.i(TAG, "Using recent last known location: ${location.latitude}, ${location.longitude}")
                        sendLocationResponse(location.latitude, location.longitude)
                        onComplete(true)
                    } else {
                        Log.d(TAG, "Last known location is null or too old")
                        onComplete(false)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.w(TAG, "Failed to get last location: ${exception.message}")
                    onComplete(false)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last location: ${e.message}")
            onComplete(false)
        }
    }

    private fun isLocationRecent(location: android.location.Location): Boolean {
        val locationAge = System.currentTimeMillis() - location.time
        val maxAge = 5 * 60 * 1000L // 5 minutes
        return locationAge < maxAge
    }

    private fun getCurrentLocationImproved(onComplete: (Boolean) -> Unit) {
        try {
            if (!hasLocationPermission()) {
                onComplete(false)
                return
            }

            val cancellationTokenSource = CancellationTokenSource()
            var isCompleted = false

            // Set timeout
            locationTimeoutRunnable = Runnable {
                if (!isCompleted) {
                    isCompleted = true
                    cancellationTokenSource.cancel()
                    Log.w(TAG, "Current location request timed out")
                    onComplete(false)
                }
            }
            locationTimeoutHandler?.postDelayed(locationTimeoutRunnable!!, LOCATION_TIMEOUT)

            // Try with high accuracy first, then balanced
            val priorities = listOf(Priority.PRIORITY_HIGH_ACCURACY, Priority.PRIORITY_BALANCED_POWER_ACCURACY)

            getCurrentLocationWithPriority(priorities, 0, cancellationTokenSource) { success ->
                if (!isCompleted) {
                    isCompleted = true
                    locationTimeoutRunnable?.let { locationTimeoutHandler?.removeCallbacks(it) }
                    onComplete(success)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting current location: ${e.message}")
            onComplete(false)
        }
    }

    private fun getCurrentLocationWithPriority(
        priorities: List<Int>,
        currentIndex: Int,
        cancellationTokenSource: CancellationTokenSource,
        onComplete: (Boolean) -> Unit
    ) {
        if (currentIndex >= priorities.size || cancellationTokenSource.token.isCancellationRequested) {
            onComplete(false)
            return
        }

        val priority = priorities[currentIndex]
        Log.d(TAG, "Trying getCurrentLocation with priority: $priority")

        try {
            fusedLocationClient.getCurrentLocation(priority, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    if (location != null && !cancellationTokenSource.token.isCancellationRequested) {
                        Log.i(TAG, "Current location obtained with priority $priority: ${location.latitude}, ${location.longitude}")
                        sendLocationResponse(location.latitude, location.longitude)
                        onComplete(true)
                    } else {
                        Log.d(TAG, "Location null with priority $priority, trying next priority")
                        getCurrentLocationWithPriority(priorities, currentIndex + 1, cancellationTokenSource, onComplete)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.w(TAG, "getCurrentLocation failed with priority $priority: ${exception.message}")
                    getCurrentLocationWithPriority(priorities, currentIndex + 1, cancellationTokenSource, onComplete)
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error: ${e.message}")
            onComplete(false)
        }
    }

    private fun requestLocationUpdates() {
        try {
            if (!hasLocationPermission()) {
                Log.w(TAG, "No permission for location updates")
                sendSimpleResponse()
                return
            }

            Log.d(TAG, "Requesting location updates as fallback")

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(500L)
                .setMaxUpdateDelayMillis(2000L)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        Log.i(TAG, "Location from updates: ${location.latitude}, ${location.longitude}")
                        sendLocationResponse(location.latitude, location.longitude)
                        stopLocationUpdates()
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            // Stop location updates after timeout
            Handler(Looper.getMainLooper()).postDelayed({
                stopLocationUpdates()
                sendSimpleResponse()
            }, LOCATION_TIMEOUT)

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error for location updates: ${e.message}")
            sendSimpleResponse()
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location updates: ${e.message}")
            sendSimpleResponse()
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
                locationCallback = null
                Log.d(TAG, "Location updates stopped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping location updates: ${e.message}")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
                isLocationEnabled()
    }

    private fun getLastKnownLocation() {
        // This method is kept for compatibility but replaced by improved version
        getLastKnownLocationImproved { success ->
            if (!success) {
                getCurrentLocationImproved { success2 ->
                    if (!success2) {
                        sendSimpleResponse()
                    }
                }
            }
        }
    }

    private fun getCurrentLocationWithTimeout() {
        // This method is kept for compatibility but replaced by improved version
        getCurrentLocationImproved { success ->
            if (!success) {
                sendSimpleResponse()
            }
        }
    }

    private fun sendLocationResponse(latitude: Double, longitude: Double) {
        val phoneNumber = senderNumber ?: return

        try {
            Log.i(TAG, "Preparing to send location SMS to: $phoneNumber")

            // Check SMS permission
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No SMS send permission")
                return
            }

            val smsManager = SmsManager.getDefault()
            val timeStamp = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.getDefault()).format(java.util.Date())

            // Create shorter message to avoid SMS length issues
            val locationMessage = "📱 PHONE FOUND!\n📍 https://maps.google.com/?q=$latitude,$longitude\n⏰ $timeStamp\n🔊 Ringing now"

            Log.d(TAG, "Sending SMS: $locationMessage")

            // Send SMS
            val parts = smsManager.divideMessage(locationMessage)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                Log.i(TAG, "Multi-part SMS sent (${parts.size} parts)")
            } else {
                smsManager.sendTextMessage(phoneNumber, null, locationMessage, null, null)
                Log.i(TAG, "Single SMS sent")
            }

            // Update notification
            updateNotification("📍 Location sent via SMS", "Phone ringing • Auto-stop in 45s")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send location SMS: ${e.message}", e)
            sendSimpleResponse()
        }
    }

    private fun sendSimpleResponse() {
        val phoneNumber = senderNumber ?: return

        try {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No SMS permission for simple response")
                return
            }

            Log.i(TAG, "Sending simple response to: $phoneNumber")

            val smsManager = SmsManager.getDefault()
            val timeStamp = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.getDefault()).format(java.util.Date())
            val message = "📱 PHONE FOUND!\n🔊 Ringing now\n⏰ $timeStamp\n📍 Location unavailable"

            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.i(TAG, "Simple SMS response sent")

            // Update notification
            updateNotification("📱 Response sent via SMS", "Phone ringing • Auto-stop in 45s")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send simple response: ${e.message}", e)
        }
    }

    private fun updateNotification(title: String, content: String) {
        try {
            val notification = createNotification(title, content)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    private fun stopPhoneFinder() {
        if (!isRinging) return

        isRinging = false
        Log.i(TAG, "Stopping phone finder")

        // Cancel auto-stop timer
        autoStopRunnable?.let { autoStopHandler?.removeCallbacks(it) }

        stopAlarmSound()
        stopVibration()

        // Update notification
        updateNotification("✅ Phone Finder Stopped", "Finder completed successfully")

        // Remove notification after 3 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            stopForeground(true)
        }, 3000)
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            mediaPlayer = null
            Log.d(TAG, "Alarm sound stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping sound: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            Log.d(TAG, "Vibration stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping vibration: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        locationTimeoutRunnable?.let { locationTimeoutHandler?.removeCallbacks(it) }
        stopPhoneFinder()
        Log.d(TAG, "PhoneFinderService destroyed")
    }
}