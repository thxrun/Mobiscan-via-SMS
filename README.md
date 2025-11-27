📱 Mobiscan – Mobile Security App
Phase 1 Triggering Activities via SMS

Mobiscan is a powerful Android security application that provides remote phone finder, GPS location tracking, and SMS-based security control.
This document covers Phase 1 implementation only (SIM card tracking will be Phase 2).

🌟 Features (Phase 1)
🔍 Location Services

High-accuracy GPS location

Real-time latitude/longitude retrieval

Google Maps link generation

Background location service

Firebase location history storage

📱 Phone Finder

Trigger loud alarm through SMS

Vibration + ringtone activation

Works even if app is closed

30-second automatic stop

🔒 Device Security

Remote device locking using SMS

Device Admin permission support

Emergency lock & protect commands

💬 SMS Command Execution

App runs silently in the background

Processes commands instantly

Responds with location / status

📨 SMS Commands (Phase 1 Only)
📌 Phone Finder Commands
Command	Action
MOBI RING	Play loud alarm
MOBI BUZZ	Play sound + vibrate
MOBI SOUND	Trigger alarm
MOBI ALARM	Overwrite all + max volume alarm
MOBI FINDME	Full finder mode
📌 Location Commands
Command	Action
MOBI LOCATION	Send current coordinates
MOBI WHERE	Quick location
MOBI LOCATE	Fetch GPS location
MOBI GPS	Get GPS + accuracy
MOBI TRACK	Send Google Maps link
📌 Security Commands
Command	Action
MOBI LOCK	Lock device immediately
MOBI SECURE	Activate security mode
MOBI PROTECT	Enable emergency protection
MOBI EMERGENCY	Lock + display warning
📌 Help Commands
Command	Action
MOBI HELP	Send all commands list
MOBI INFO	App info
MOBI COMMANDS	Commands summary
🔄 Technical Flow (Phase 1)
1️⃣ SMS Command Flow

User sends SMS command from another device

SmsReceiver intercepts message

Command is parsed and validated

Appropriate module is triggered

Action performed (alarm / lock / location)

Optional: reply SMS with result

2️⃣ Location Tracking Flow

Command received: MOBI LOCATION

LocationService starts in background

Fetches GPS coordinates

Generates Google Maps link

Sends SMS back with location

Uploads coordinates to Firebase

3️⃣ Phone Finder Flow

Command received: MOBI RING or MOBI ALARM

AlarmService starts

Maximum volume set

Loud alarm + vibration

Auto-shutdown after timer

4️⃣ Device Lock Flow

Command received: MOBI LOCK

DeviceAdminReceiver triggers lockNow()

Screen locks instantly

🛠️ Architecture (Phase 1)
📌 Core Components
1. SmsReceiver

Detects and processes all SMS commands

Command parser

Triggers appropriate actions

2. AlarmService

High-volume alarm

Vibration pattern

Works in background

3. LocationService

GPS provider access

FusedLocationProviderClient

Firebase database write

SMS response with map link

4. DeviceAdminHandler

Handles lock permissions

Executes device lock

5. MainActivity

UI for permissions

Setup and testing buttons

Background permissions management

📤 Permissions Required
📍 Location
ACCESS_FINE_LOCATION  
ACCESS_COARSE_LOCATION  
ACCESS_BACKGROUND_LOCATION

💬 SMS
RECEIVE_SMS  
READ_SMS  
SEND_SMS

📱 Phone
READ_PHONE_STATE

⚙️ System
VIBRATE  
WAKE_LOCK  
FOREGROUND_SERVICE

🗺️ Sample SMS Location Output
📍 LOCATION UPDATE

📅 Time: 2024-12-15 14:30:55
📍 Coordinates: 40.7128, -74.0060
🎯 Accuracy: 5m
🗺️ Map: https://maps.google.com/?q=40.7128,-74.0060

🖼️ Screenshots (Optional Section)

You can upload images like:

App home screen

Permission screens

SMS output

Alarm screen

Firebase dashboard

Format example:

![Home Screen](images/home.png)
![SMS Command](images/sms.png)

🔐 Privacy Notice

Mobiscan collects only essential security information, such as GPS location during command execution.
No data is accessed or shared without user permission.
All stored data is controlled by the device owner.

⚠️ Legal Disclaimer

This app must be used only on devices you own or have legal authority to monitor.
Unauthorized tracking or monitoring may violate privacy laws.
The developer is not responsible for misuse.

📞 Support

For issues, improvements, or feature requests, open a GitHub Issue or contact the developer.