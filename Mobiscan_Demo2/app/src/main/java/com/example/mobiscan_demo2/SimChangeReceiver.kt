package com.example.mobiscan_demo2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.*
import com.example.mobiscan_demo2.SecurityUtils

class SimChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimChangeReceiver"
        private const val PREFS_NAME = "MobiScanPrefs"
        private const val KEY_ALTERNATE_NUMBER = "alternate_number"
        private const val KEY_LAST_SIM_STATE = "last_sim_state"
        private const val KEY_LAST_SIM_SERIAL = "last_sim_serial"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.telephony.action.SIM_CARD_STATE_CHANGED" -> {
                Log.d(TAG, "SIM card state changed")
                handleSimStateChange(context, intent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed, checking SIM state")
                checkSimStateOnBoot(context)
            }
            else -> {
                Log.d(TAG, "Received action: ${intent.action}")
            }
        }
    }

    private fun handleSimStateChange(context: Context, intent: Intent) {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_PHONE_STATE permission not granted")
                return
            }

            // Get current SIM information with error handling
            val currentSimState = try {
                telephonyManager.simState
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get SIM state: ${e.message}", e)
                TelephonyManager.SIM_STATE_UNKNOWN
            }
            
            val rawSerial: String = try {
                telephonyManager.simSerialNumber ?: "unknown"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get SIM serial: ${e.message}", e)
                "unknown"
            }
            val currentSimSerialHashed = SecurityUtils.hashIdentifier(rawSerial)
            val currentSimSerialMasked = SecurityUtils.maskIdentifier(rawSerial)
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastSimState = prefs.getInt(KEY_LAST_SIM_STATE, TelephonyManager.SIM_STATE_UNKNOWN)
            val lastSimSerial = prefs.getString(KEY_LAST_SIM_SERIAL, "unknown") ?: "unknown"
            
            Log.d(TAG, "SIM State: $currentSimState (was: $lastSimState)")
            Log.d(TAG, "SIM Serial: $currentSimSerialMasked (stored: hash)")

            // Validate SIM state values
            if (currentSimState == TelephonyManager.SIM_STATE_UNKNOWN && lastSimState == TelephonyManager.SIM_STATE_UNKNOWN) {
                Log.w(TAG, "Both current and last SIM states are UNKNOWN, skipping change detection")
                return
            }

            // Check if SIM was removed or changed
            val simRemoved = (lastSimState == TelephonyManager.SIM_STATE_READY) && 
                           (currentSimState == TelephonyManager.SIM_STATE_ABSENT || 
                            currentSimState == TelephonyManager.SIM_STATE_UNKNOWN)

            val simInserted = (lastSimState == TelephonyManager.SIM_STATE_ABSENT || 
                             lastSimState == TelephonyManager.SIM_STATE_UNKNOWN) && 
                            (currentSimState == TelephonyManager.SIM_STATE_READY)

            val simChanged = lastSimSerial != "unknown" && currentSimSerialHashed != "unknown" && 
                           lastSimSerial != currentSimSerialHashed

            if (simRemoved || simInserted || simChanged) {
                Log.i(TAG, "SIM change detected: removed=$simRemoved, inserted=$simInserted, changed=$simChanged")
                handleSimChange(context, simRemoved, simInserted, simChanged, currentSimState, currentSimSerialMasked)
            }

            // Update stored values only if we got valid data
            if (currentSimState != TelephonyManager.SIM_STATE_UNKNOWN || currentSimSerialHashed != "unknown") {
                prefs.edit()
                    .putInt(KEY_LAST_SIM_STATE, currentSimState)
                    .putString(KEY_LAST_SIM_SERIAL, currentSimSerialHashed)
                    .apply()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling SIM state change: ${e.message}", e)
        }
    }

    private fun checkSimStateOnBoot(context: Context) {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_PHONE_STATE permission not granted on boot")
                return
            }

            // Get current SIM information with error handling
            val currentSimState = try {
                telephonyManager.simState
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get SIM state on boot: ${e.message}", e)
                TelephonyManager.SIM_STATE_UNKNOWN
            }
            
            val bootRawSerial: String = try {
                telephonyManager.simSerialNumber ?: "unknown"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get SIM serial on boot: ${e.message}", e)
                "unknown"
            }
            val bootHashedSerial = SecurityUtils.hashIdentifier(bootRawSerial)
            val bootMaskedSerial = SecurityUtils.maskIdentifier(bootRawSerial)
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // Store current state on boot only if we got valid data
            if (currentSimState != TelephonyManager.SIM_STATE_UNKNOWN || bootHashedSerial != "unknown") {
                prefs.edit()
                    .putInt(KEY_LAST_SIM_STATE, currentSimState)
                    .putString(KEY_LAST_SIM_SERIAL, bootHashedSerial)
                    .apply()

                Log.d(TAG, "Boot: SIM State=$currentSimState, Serial=$bootMaskedSerial")
            } else {
                Log.w(TAG, "Boot: Could not get valid SIM state, will retry later")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking SIM state on boot: ${e.message}", e)
        }
    }

    private fun handleSimChange(context: Context, simRemoved: Boolean, simInserted: Boolean, simChanged: Boolean, currentState: Int, currentSerialMasked: String) {
        val alternateNumber = getAlternateNumber(context)
        
        if (alternateNumber.isNullOrBlank()) {
            Log.w(TAG, "No alternate number configured, skipping notification")
            return
        }

        val timestamp = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date())
        
        val changeType = when {
            simRemoved -> "REMOVED"
            simInserted -> "INSERTED"
            simChanged -> "CHANGED"
            else -> "UNKNOWN"
        }

        Log.i(TAG, "SIM $changeType detected at $timestamp")

        // Start the tracking service
        val serviceIntent = Intent(context, SimTrackingService::class.java).apply {
            putExtra("change_type", changeType)
            putExtra("alternate_number", alternateNumber)
            putExtra("timestamp", timestamp)
            putExtra("sim_serial", currentSerialMasked)
            putExtra("sim_state", currentState)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun getAlternateNumber(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ALTERNATE_NUMBER, null)
    }
}
