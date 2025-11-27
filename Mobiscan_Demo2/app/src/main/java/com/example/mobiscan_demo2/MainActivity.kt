package com.example.mobiscan_demo2

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BUZZ_DURATION = 30000L // 30 seconds
    }

    // Data class for SIM status results
    sealed class SimStatusResult {
        data class Success(
            val status: String,
            val simState: Int,
            val simSerial: String,
            val networkOperator: String,
            val phoneNumber: String
        ) : SimStatusResult()
        
        data class Error(val error: String) : SimStatusResult()
        
        val isSuccess: Boolean get() = this is Success
    }

    // UI Components
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var database: DatabaseReference
    private lateinit var statusText: TextView
    private lateinit var ringButton: Button
    private lateinit var locationButton: Button
    private lateinit var lockButton: Button
    private lateinit var permissionsButton: Button
    private lateinit var alternateNumberInput: EditText
    private lateinit var saveAlternateNumberButton: Button
    private lateinit var refreshSimStatusButton: Button

    // Device Admin
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var compName: ComponentName

    // Audio & Vibration
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var isRinging = false

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val smsReceiveGranted = permissions[Manifest.permission.RECEIVE_SMS] ?: false
        val smsSendGranted = permissions[Manifest.permission.SEND_SMS] ?: false
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        val phoneNumbersGranted = permissions[Manifest.permission.READ_PHONE_NUMBERS] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        when {
            locationGranted && smsReceiveGranted && smsSendGranted && phoneStateGranted && phoneNumbersGranted && notificationGranted -> {
                Toast.makeText(this, "✅ All permissions granted! Mobile finder and SIM tracking fully ready.", Toast.LENGTH_LONG).show()
                getCurrentLocation()
                initializeSimState()
                checkSimTrackingStatus()
            }
            locationGranted && smsReceiveGranted && smsSendGranted && phoneStateGranted && phoneNumbersGranted -> {
                Toast.makeText(this, "✅ Core permissions granted! Mobile finder and SIM tracking ready.", Toast.LENGTH_LONG).show()
                getCurrentLocation()
                initializeSimState()
                checkSimTrackingStatus()
            }
            locationGranted && smsReceiveGranted && smsSendGranted -> {
                Toast.makeText(this, "✅ Core permissions granted! Mobile finder ready. Phone permissions needed for SIM tracking.", Toast.LENGTH_LONG).show()
                getCurrentLocation()
            }
            locationGranted -> {
                Toast.makeText(this, "✅ Location granted. SMS and phone permissions needed for full functionality.", Toast.LENGTH_LONG).show()
                getCurrentLocation()
            }
            smsReceiveGranted || smsSendGranted -> {
                Toast.makeText(this, "✅ SMS permission granted. Location and phone permissions needed for tracking.", Toast.LENGTH_SHORT).show()
            }
            phoneStateGranted || phoneNumbersGranted -> {
                Toast.makeText(this, "✅ Phone permissions granted. Location and SMS permissions needed for full functionality.", Toast.LENGTH_SHORT).show()
                initializeSimState()
            }
            else -> {
                Toast.makeText(this, "⚠️ Permissions needed for full functionality. Mobile finder and SIM tracking may not work.", Toast.LENGTH_SHORT).show()
            }
        }
        updateSystemStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeComponents()
        setupClickListeners()
        handleIntentExtras()
        loadAlternateNumber()
        requestPermissions()
        
        // Initialize SIM state if permissions are already granted
        if (hasPhonePermissions()) {
            initializeSimState()
        }
        
        updateSystemStatus()
    }

    private fun initializeComponents() {
        // Firebase & Location
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        database = FirebaseDatabase.getInstance().reference

        // UI Elements
        statusText = findViewById(R.id.statusText)
        ringButton = findViewById(R.id.ringButton)
        locationButton = findViewById(R.id.locationButton)
        lockButton = findViewById(R.id.lockButton)
        permissionsButton = findViewById(R.id.permissionsButton)
        alternateNumberInput = findViewById(R.id.alternateNumberInput)
        saveAlternateNumberButton = findViewById(R.id.saveAlternateNumberButton)
        refreshSimStatusButton = findViewById(R.id.refreshSimStatusButton)

        // Vibrator initialization
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        // Device Admin
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        compName = ComponentName(this, MyDeviceAdminReceiver::class.java)

        Log.d(TAG, "MainActivity components initialized")
    }

    private fun setupClickListeners() {
        ringButton.setOnClickListener {
            startPhoneFinder()
        }

        locationButton.setOnClickListener {
            if (hasLocationPermission()) {
                getCurrentLocation()
            } else {
                requestPermissions()
            }
        }

        lockButton.setOnClickListener {
            lockDevice()
        }

        permissionsButton.setOnClickListener {
            requestPermissions()
        }

        saveAlternateNumberButton.setOnClickListener {
            saveAlternateNumber()
        }

        refreshSimStatusButton.setOnClickListener {
            refreshSimStatus()
        }

        // Add long press to show detailed diagnostics
        refreshSimStatusButton.setOnLongClickListener {
            showSimDiagnostics()
            true
        }

        // Add long press to test SIM tracking
        saveAlternateNumberButton.setOnLongClickListener {
            testSimTracking()
            true
        }
    }

    private fun handleIntentExtras() {
        if (intent.getBooleanExtra("sms_trigger", false)) {
            val senderNumber = intent.getStringExtra("sender_number") ?: "Unknown"
            val triggerWord = intent.getStringExtra("trigger_word") ?: "FINDME"
            Toast.makeText(this, "📱 SMS Trigger from: $senderNumber\nKeyword: $triggerWord", Toast.LENGTH_LONG).show()
            startPhoneFinder()
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (!hasLocationPermission()) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (!hasSmsPermissions()) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
            permissionsToRequest.add(Manifest.permission.READ_SMS)
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }

        if (!hasPhonePermissions()) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
            permissionsToRequest.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Toast.makeText(this, "✅ All permissions already granted!", Toast.LENGTH_SHORT).show()
            getCurrentLocation()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasSmsPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasPhonePermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
    }

    private fun getCurrentLocation() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "⚠️ Location permission required", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.text = "🔍 Getting location..."
        locationButton.isEnabled = false

        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    locationButton.isEnabled = true
                    if (location != null) {
                        val lat = location.latitude
                        val lng = location.longitude
                        val accuracy = location.accuracy
                        val timestamp = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date())

                        val locationInfo = """
                            📍 Current Location:
                            Latitude: $lat
                            Longitude: $lng
                            Accuracy: ${accuracy}m
                            Time: $timestamp
                            
                            🗺️ Google Maps: 
                            https://maps.google.com/?q=$lat,$lng
                        """.trimIndent()

                        statusText.text = locationInfo
                        saveLocationToFirebase(lat, lng, timestamp)
                        Log.i(TAG, "Location obtained: $lat, $lng (accuracy: ${accuracy}m)")
                    } else {
                        statusText.text = "❌ Unable to get current location.\nPlease check GPS and permissions."
                        Log.w(TAG, "Location is null")
                    }
                }
                .addOnFailureListener { exception ->
                    locationButton.isEnabled = true
                    statusText.text = "❌ Location error: ${exception.message}\nPlease enable GPS and check permissions."
                    Log.e(TAG, "Location error: ${exception.message}", exception)
                }
        } catch (e: SecurityException) {
            locationButton.isEnabled = true
            statusText.text = "❌ Location permission denied"
            Log.e(TAG, "Location permission error", e)
        }
    }

    private fun saveLocationToFirebase(lat: Double, lng: Double, timestamp: String) {
        try {
            val locationData = mapOf(
                "latitude" to lat,
                "longitude" to lng,
                "timestamp" to timestamp,
                "accuracy" to "high"
            )

            database.child("mobile_finder").child("current_location").setValue(locationData)
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

    private fun startPhoneFinder() {
        if (isRinging) {
            Toast.makeText(this, "📱 Phone finder already active", Toast.LENGTH_SHORT).show()
            return
        }

        isRinging = true
        Log.i(TAG, "Starting phone finder from MainActivity")

        Toast.makeText(this, "🔊 Phone Finder Started!\nRinging for 30 seconds...", Toast.LENGTH_LONG).show()

        ringButton.isEnabled = false
        ringButton.text = "🔊 RINGING..."

        startAlarmSound()
        startVibrationPattern()

        Handler(Looper.getMainLooper()).postDelayed({
            if (isRinging) {
                stopPhoneFinder()
                Toast.makeText(this, "✅ Phone finder auto-stopped", Toast.LENGTH_SHORT).show()
            }
        }, BUZZ_DURATION)
    }

    private fun startAlarmSound() {
        try {
            stopAlarmSound()
            val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, alarmUri)
                isLooping = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                }

                prepare()
                start()
            }

            setMaximumVolume()
            Log.d(TAG, "Alarm sound started")

        } catch (e: Exception) {
            Log.e(TAG, "Sound error: ${e.message}", e)
            Toast.makeText(this, "⚠️ Unable to play alarm sound", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setMaximumVolume() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            Log.d(TAG, "Volume set to maximum")
        } catch (e: Exception) {
            Log.w(TAG, "Volume control failed: ${e.message}")
        }
    }

    private fun startVibrationPattern() {
        try {
            val pattern = longArrayOf(0, 1000, 300, 1000, 300, 1000, 500)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
            Log.d(TAG, "Vibration started")
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun stopPhoneFinder() {
        if (!isRinging) return
        isRinging = false

        stopAlarmSound()
        stopVibration()

        ringButton.isEnabled = true
        ringButton.text = "🔊 RING"

        Toast.makeText(this, "✅ Phone finder stopped", Toast.LENGTH_SHORT).show()
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
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

    // 🔒 Lock Device Feature
    private fun lockDevice() {
        if (devicePolicyManager.isAdminActive(compName)) {
            devicePolicyManager.lockNow()
            Toast.makeText(this, "🔒 Device Locked", Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "📱 Allow device lock for security.")
            }
            startActivity(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity paused")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity resumed")
        if (isRinging) {
            ringButton.isEnabled = false
            ringButton.text = "🔊 RINGING..."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPhoneFinder()
        Log.d(TAG, "MainActivity destroyed")
    }

    // SIM Tracking Methods
    private fun saveAlternateNumber() {
        val phoneNumber = alternateNumberInput.text.toString().trim()
        
        if (phoneNumber.isEmpty()) {
            Toast.makeText(this, "⚠️ Please enter a phone number", Toast.LENGTH_SHORT).show()
            return
        }

        // Basic phone number validation
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.length < 10) {
            Toast.makeText(this, "⚠️ Please enter a valid phone number", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("alternate_number", cleanNumber).apply()

        Toast.makeText(this, "✅ Alternate number saved: $cleanNumber", Toast.LENGTH_LONG).show()
        Log.i(TAG, "Alternate number saved: $cleanNumber")
        
        // Initialize SIM state after saving number
        initializeSimState()
    }

    private fun loadAlternateNumber() {
        val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
        val savedNumber = prefs.getString("alternate_number", "")
        alternateNumberInput.setText(savedNumber)
        
        // Initialize SIM state when loading
        if (savedNumber?.isNotBlank() == true) {
            initializeSimState()
        }
    }

    private fun initializeSimState() {
        try {
            if (!hasPhonePermissions()) {
                Log.w(TAG, "Phone permissions not granted, cannot initialize SIM state")
                return
            }

            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            val currentSimState = telephonyManager.simState
            val currentSimSerial = telephonyManager.simSerialNumber ?: "unknown"
            
            val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
            
            // Store current state
            val hashed = SecurityUtils.hashIdentifier(currentSimSerial)
            prefs.edit()
                .putInt("last_sim_state", currentSimState)
                .putString("last_sim_serial", hashed)
                .apply()

            Log.i(TAG, "SIM state initialized: State=$currentSimState, Serial=${SecurityUtils.maskIdentifier(currentSimSerial)}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SIM state: ${e.message}", e)
            
            // Schedule retry after a delay
            Handler(Looper.getMainLooper()).postDelayed({
                if (hasPhonePermissions()) {
                    Log.d(TAG, "Retrying SIM state initialization...")
                    initializeSimState()
                }
            }, 5000) // Retry after 5 seconds
        }
    }

    private fun initializeSimStateWithRetry(maxRetries: Int = 3) {
        var retryCount = 0
        
        fun attemptInitialization() {
            try {
                if (!hasPhonePermissions()) {
                    Log.w(TAG, "Phone permissions not granted, cannot initialize SIM state")
                    return
                }

                val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                
                val currentSimState = telephonyManager.simState
                val currentSimSerial = telephonyManager.simSerialNumber ?: "unknown"
                
                val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
                
                // Store current state
                val hashed = SecurityUtils.hashIdentifier(currentSimSerial)
                prefs.edit()
                    .putInt("last_sim_state", currentSimState)
                    .putString("last_sim_serial", hashed)
                    .apply()

                Log.i(TAG, "SIM state initialized: State=$currentSimState, Serial=${SecurityUtils.maskIdentifier(currentSimSerial)}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SIM state (attempt ${retryCount + 1}): ${e.message}", e)
                retryCount++
                
                if (retryCount < maxRetries) {
                    // Schedule retry after a delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (hasPhonePermissions()) {
                            Log.d(TAG, "Retrying SIM state initialization (attempt ${retryCount + 1})...")
                            attemptInitialization()
                        }
                    }, (5000 * retryCount).toLong()) // Exponential backoff
                } else {
                    Log.e(TAG, "Failed to initialize SIM state after $maxRetries attempts")
                    Toast.makeText(this, "⚠️ Failed to initialize SIM status after multiple attempts", Toast.LENGTH_LONG).show()
                }
            }
        }
        
        attemptInitialization()
    }

    private fun checkSimTrackingStatus() {
        val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
        val alternateNumber = prefs.getString("alternate_number", "")
        
        if (alternateNumber.isNullOrBlank()) {
            Toast.makeText(this, "📱 Configure SIM tracking by setting an alternate number", Toast.LENGTH_LONG).show()
        } else {
            // Check current SIM status
            val simStatus = getCurrentSimStatus()
            if (simStatus.isSuccess) {
                val successStatus = simStatus as SimStatusResult.Success
                Toast.makeText(this, "✅ SIM tracking active for: $alternateNumber\nSIM Status: ${successStatus.status}", Toast.LENGTH_LONG).show()
            } else {
                val errorStatus = simStatus as SimStatusResult.Error
                Toast.makeText(this, "⚠️ SIM tracking active but status check failed: ${errorStatus.error}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getCurrentSimStatus(): SimStatusResult {
        return try {
            if (!hasPhonePermissions()) {
                SimStatusResult.Error("Phone permissions not granted")
            } else {
                val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                
                // Check if device has telephony capability
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                    return SimStatusResult.Error("Device does not support telephony")
                }
                
                val simState = telephonyManager.simState
                // ICCID access is restricted on Android 10+. Read defensively and do not fail if blocked
                val simSerialSafe: String = try {
                    telephonyManager.simSerialNumber ?: "REDACTED"
                } catch (e: SecurityException) {
                    Log.w(TAG, "ICCID access restricted by platform: ${e.message}")
                    "RESTRICTED"
                } catch (e: Exception) {
                    Log.w(TAG, "ICCID read failed: ${e.message}")
                    "UNKNOWN"
                }
                val networkOperator = telephonyManager.networkOperatorName
                val phoneNumber = telephonyManager.line1Number
                
                val statusText = when (simState) {
                    TelephonyManager.SIM_STATE_READY -> "READY"
                    TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                    TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
                    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                    else -> "UNKNOWN"
                }
                
                SimStatusResult.Success(
                    status = statusText,
                    simState = simState,
                    simSerial = simSerialSafe,
                    networkOperator = networkOperator ?: "unknown",
                    phoneNumber = phoneNumber ?: "unknown"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SIM status: ${e.message}", e)
            SimStatusResult.Error("Exception: ${e.message}")
        }
    }

    private fun hasSimCard(): Boolean {
        return try {
            if (!hasPhonePermissions()) {
                false
            } else {
                val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val simState = telephonyManager.simState
                simState == TelephonyManager.SIM_STATE_READY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking SIM card presence: ${e.message}", e)
            false
        }
    }

    private fun runSimDiagnostics(): String {
        val diagnostics = StringBuilder()
        diagnostics.append("🔍 SIM DIAGNOSTICS REPORT\n\n")
        
        try {
            // Check permissions
            val phonePermission = hasPhonePermissions()
            diagnostics.append("📱 Phone Permissions: ${if (phonePermission) "✅ GRANTED" else "❌ DENIED"}\n")
            
            if (!phonePermission) {
                diagnostics.append("   → Grant READ_PHONE_STATE and READ_PHONE_NUMBERS\n")
                return diagnostics.toString()
            }
            
            // Check device capabilities
            val hasTelephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            diagnostics.append("📱 Telephony Support: ${if (hasTelephony) "✅ YES" else "❌ NO"}\n")
            
            if (!hasTelephony) {
                diagnostics.append("   → Device does not support SIM cards\n")
                return diagnostics.toString()
            }
            
            // Get telephony manager
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            // Check SIM state
            val simState = telephonyManager.simState
            val simStateText = when (simState) {
                TelephonyManager.SIM_STATE_READY -> "READY"
                TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                else -> "UNKNOWN"
            }
            diagnostics.append("📋 SIM State: $simStateText (Code: $simState)\n")
            
            // ICCID is restricted on Android 10+ for non-privileged apps
            val iccidNote: String = try {
                val s = telephonyManager.simSerialNumber
                if (s != null) "🔢 SIM Serial: ${SecurityUtils.maskIdentifier(s)}" else "🔢 SIM Serial: ❌ NULL"
            } catch (se: SecurityException) {
                "🔢 SIM Serial: 🚫 Restricted by Android (ICCID)"
            } catch (e: Exception) {
                "🔢 SIM Serial: ❌ Unavailable"
            }
            diagnostics.append(iccidNote + "\n")
            
            // Check network operator
            val networkOperator = telephonyManager.networkOperatorName
            diagnostics.append("🌐 Network: ${networkOperator ?: "unknown"}\n")
            
            // Check phone number (may be empty on many carriers)
            val phoneNumber = telephonyManager.line1Number
            diagnostics.append("📞 Phone Number: ${phoneNumber ?: "unknown"}\n")
            
            // Check stored values
            val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
            val lastSimState = prefs.getInt("last_sim_state", -1)
            val lastSimSerial = prefs.getString("last_sim_serial", "none")
            
            diagnostics.append("💾 Stored SIM State: $lastSimState\n")
            diagnostics.append("💾 Stored SIM Serial (hash): ${lastSimSerial?.take(10) ?: "none"}…\n")
            
            // Recommendations
            diagnostics.append("\n💡 RECOMMENDATIONS:\n")
            when {
                simState == TelephonyManager.SIM_STATE_ABSENT -> {
                    diagnostics.append("   • Insert SIM card\n")
                    diagnostics.append("   • Check SIM card orientation\n")
                    diagnostics.append("   • Clean SIM card contacts\n")
                }
                simState == TelephonyManager.SIM_STATE_PIN_REQUIRED -> {
                    diagnostics.append("   • Enter SIM PIN code\n")
                    diagnostics.append("   • Check carrier settings\n")
                }
                simState == TelephonyManager.SIM_STATE_PUK_REQUIRED -> {
                    diagnostics.append("   • Contact carrier for PUK code\n")
                    diagnostics.append("   • SIM may be locked\n")
                }
                simState == TelephonyManager.SIM_STATE_NETWORK_LOCKED -> {
                    diagnostics.append("   • SIM is network locked\n")
                    diagnostics.append("   • Contact carrier\n")
                }
                simState == TelephonyManager.SIM_STATE_UNKNOWN -> {
                    diagnostics.append("   • Restart device\n")
                    diagnostics.append("   • Check SIM card insertion\n")
                    diagnostics.append("   • Try different SIM card\n")
                }
                simState == TelephonyManager.SIM_STATE_READY -> {
                    diagnostics.append("   • SIM is working correctly\n")
                    diagnostics.append("   • All systems operational\n")
                }
            }
            
        } catch (e: Exception) {
            diagnostics.append("❌ Error during diagnostics: ${e.message}\n")
            diagnostics.append("   • Check app permissions\n")
            diagnostics.append("   • Restart device\n")
            diagnostics.append("   • Contact support\n")
        }
        
        return diagnostics.toString()
    }

    private fun testSimTracking() {
        val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
        val alternateNumber = prefs.getString("alternate_number", "")
        
        if (alternateNumber.isNullOrBlank()) {
            Toast.makeText(this, "⚠️ Please set an alternate number first", Toast.LENGTH_SHORT).show()
            return
        }

        // Get current SIM status for test
        val simStatus = getCurrentSimStatus()
        if (simStatus.isSuccess) {
            val status = simStatus as SimStatusResult.Success
            
            // Start test tracking service
            val serviceIntent = Intent(this, SimTrackingService::class.java).apply {
                putExtra("change_type", "TEST")
                putExtra("alternate_number", alternateNumber)
                putExtra("timestamp", SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date()))
                putExtra("sim_serial", status.simSerial)
                putExtra("sim_state", status.simState)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "🧪 Test SIM tracking started\nSIM Status: ${status.status}", Toast.LENGTH_LONG).show()
        } else {
            val errorStatus = simStatus as SimStatusResult.Error
            Toast.makeText(this, "❌ Cannot start test: ${errorStatus.error}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras()
    }

    private fun updateSystemStatus() {
        val statusBuilder = StringBuilder()
        
        // Check permissions
        val locationGranted = hasLocationPermission()
        val smsGranted = hasSmsPermissions()
        val phoneGranted = hasPhonePermissions()
        
        statusBuilder.append("🔒 SECURITY STATUS:\n")
        statusBuilder.append("📍 Location: ${if (locationGranted) "✅" else "❌"}\n")
        statusBuilder.append("📨 SMS: ${if (smsGranted) "✅" else "❌"}\n")
        statusBuilder.append("📱 Phone: ${if (phoneGranted) "✅" else "❌"}\n")
        
        // Check SIM tracking and status
        val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
        val alternateNumber = prefs.getString("alternate_number", "") ?: ""
        statusBuilder.append("📱 SIM Tracking: ${if (alternateNumber.isNotBlank()) "✅" else "❌"}\n")
        
        // Get current SIM status
        if (phoneGranted) {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                val simStatus = getCurrentSimStatus()
                if (simStatus.isSuccess) {
                    val status = simStatus as SimStatusResult.Success
                    statusBuilder.append("📋 SIM Status: ✅ ${status.status}\n")
                    statusBuilder.append("🔢 Network: ${status.networkOperator}\n")
                    
                    // Show SIM card presence
                    if (hasSimCard()) {
                        statusBuilder.append("📱 SIM Card: ✅ Present\n")
                    } else {
                        statusBuilder.append("📱 SIM Card: ❌ Not detected\n")
                    }
                } else {
                    val errorStatus = simStatus as SimStatusResult.Error
                    statusBuilder.append("📋 SIM Status: ❌ ${errorStatus.error}\n")
                    statusBuilder.append("📱 SIM Card: ❓ Status unknown\n")
                }
            } else {
                statusBuilder.append("📋 SIM Status: ❌ Device not supported\n")
                statusBuilder.append("📱 SIM Card: ❌ No telephony support\n")
            }
        } else {
            statusBuilder.append("📋 SIM Status: ❌ No phone permission\n")
            statusBuilder.append("📱 SIM Card: ❌ Cannot check\n")
        }
        
        // Check device admin
        val adminActive = devicePolicyManager.isAdminActive(compName)
        statusBuilder.append("🔒 Device Admin: ${if (adminActive) "✅" else "❌"}\n")
        
        // Add SIM monitoring status
        val simMonitoringActive = isSimMonitoringActive()
        statusBuilder.append("📡 SIM Monitoring: ${if (simMonitoringActive) "✅ ACTIVE" else "❌ INACTIVE"}\n")
        
        statusBuilder.append("\n🚀 System ready for cyber security operations.")
        
        statusText.text = statusBuilder.toString()
    }

    private fun isSimMonitoringActive(): Boolean {
        return try {
            val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
            val alternateNumber = prefs.getString("alternate_number", "")
            val lastSimState = prefs.getInt("last_sim_state", -1)
            
            (alternateNumber?.isNotBlank() == true) && lastSimState != -1
        } catch (e: Exception) {
            Log.e(TAG, "Error checking SIM monitoring status: ${e.message}", e)
            false
        }
    }

    // Method to manually refresh SIM status
    private fun refreshSimStatus() {
        if (!hasPhonePermissions()) {
            Toast.makeText(this, "❌ Phone permissions required to check SIM status", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val simStatus = getCurrentSimStatus()
            if (simStatus.isSuccess) {
                val status = simStatus as SimStatusResult.Success
                Toast.makeText(this, 
                    "📱 SIM Status: ${status.status}\n" +
                    "🔢 Serial: ${SecurityUtils.maskIdentifier(status.simSerial)}\n" +
                    "🌐 Network: ${status.networkOperator}\n" +
                    "📞 Number: ${status.phoneNumber}", 
                    Toast.LENGTH_LONG).show()
                
                // Update stored SIM state
                val hashed = SecurityUtils.hashIdentifier(status.simSerial)
                val prefs = getSharedPreferences("MobiScanPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putInt("last_sim_state", status.simState)
                    .putString("last_sim_serial", hashed)
                    .apply()
                
                Log.i(TAG, "SIM status refreshed successfully")
            } else {
                val errorStatus = simStatus as SimStatusResult.Error
                val errorMessage = errorStatus.error
                Toast.makeText(this, "❌ Failed to get SIM status: $errorMessage", Toast.LENGTH_LONG).show()
                
                // Show diagnostics for better troubleshooting
                showSimDiagnostics()
            }
            
            // Update the status display
            updateSystemStatus()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing SIM status: ${e.message}", e)
            Toast.makeText(this, "❌ Error refreshing SIM status: ${e.message}", Toast.LENGTH_LONG).show()
            showSimDiagnostics()
        }
    }

    private fun showSimDiagnostics() {
        val diagnostics = runSimDiagnostics()
        
        // Create and show a dialog with diagnostics
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔍 SIM Diagnostics Report")
            .setMessage(diagnostics)
            .setPositiveButton("🔄 Refresh") { _, _ ->
                refreshSimStatus()
            }
            .setNegativeButton("❌ Close") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("📱 Permissions") { _, _ ->
                requestPermissions()
            }
            .show()
    }

    private fun showSimTroubleshootingTips(error: String) {
        val tips = when {
            error.contains("permission") -> """
                🔧 Troubleshooting Tips:
                • Grant Phone permissions in Settings
                • Restart the app after granting permissions
                • Check if permissions are revoked
            """.trimIndent()
            
            error.contains("telephony") -> """
                🔧 Troubleshooting Tips:
                • Device may not support SIM cards
                • Check if this is a WiFi-only device
                • Some tablets don't have telephony support
            """.trimIndent()
            
            error.contains("Exception") -> """
                🔧 Troubleshooting Tips:
                • Restart the device
                • Check if SIM card is properly inserted
                • Try removing and reinserting SIM card
                • Contact carrier if issue persists
            """.trimIndent()
            
            else -> """
                🔧 General Troubleshooting:
                • Check SIM card insertion
                • Verify carrier network
                • Restart device
                • Check app permissions
            """.trimIndent()
        }
        
        // Show tips in a longer toast or dialog
        Toast.makeText(this, tips, Toast.LENGTH_LONG).show()
    }
}
