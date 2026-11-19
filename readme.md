# Screen Time Manager

A Device Owner-powered Android self-control and app-enforcement utility built with **Kotlin** and **Jetpack Compose (Material 3)**. It enforces per-app time limits, daily open caps, continuous-session cooldowns, custom time windows, global bedtime/focus locks, anti-bypass system restrictions, and an update-friendly install guard that protects existing apps (such as banking apps) while quarantining unapproved new installs.

---

## Features

### 1. Per-App & Batch Rule Management
* **Daily & Weekend Time Limits:** Set daily foreground limits in minutes (`0` = unlimited), with optional weekend overrides (or unlimited weekends).
* **Blocked Days:** Block specific days of the week (Sunday–Saturday).
* **Schedule Windows:** Define custom time windows (including ranges that cross midnight, such as `10:00 PM – 7:00 AM`) in **Block during** or **Allow only during** mode, with 12-hour AM/PM selection and direct keyboard input.
* **Daily Launch / Open Caps:** Limit how many times an app can be launched per day.
* **Session Limits & Cooldown Breaks:** Enforce a maximum continuous foreground session length followed by a mandatory break duration.
* **Instant Manual Lock & Focus Mode:** Lock individual apps immediately or lock all managed apps for a preset duration (`15m`, `30m`, `1h`, `2h`, `4h`, or `Until midnight`).
* **Batch Configuration:** Apply rules across multiple apps at once via `BatchActivity`.

### 2. Real-Time & Self-Healing Enforcement
* **Event-Based Foreground Tracking (`UsageEngine`):** Computes exact foreground duration, distinct launch counts, and continuous session lengths from raw `UsageStatsManager` events (`RESUMED`, `PAUSED`, `STOPPED`, `DESTROYED`, `SCREEN_OFF`) rather than coarse usage buckets.
* **Idempotent Package Suspension (`Enforcer`):** Recomputes rule verdicts on every pass and uses `DevicePolicyManager.setPackagesSuspended(...)` to suspend blocked apps and automatically restore them once conditions clear.
* **Foreground Service + Watchdog (`EnforcementService` & `AppLimitWorker`):** Polls every 5 seconds while interactive (30 seconds when screen is off) and reacts immediately to `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT`, backed by a 15-minute `WorkManager` safety net and `BootReceiver` persistence across reboots and app updates.
* **Low-Time Warnings:** Sends a daily heads-up notification when ~5 minutes remain on an app's daily limit.
* **Fail-Closed Mode:** Optionally blocks all managed apps if Usage Access permission is revoked.

### 3. Anti-Bypass & Device Owner Hardening (`DeviceControl`)
Built on Android's **Device Owner** (`DevicePolicyManager`) APIs:
* **App Self-Protection:** Blocks uninstallation (`setUninstallBlocked`) and user force-stop / clear-data actions (`setUserControlDisabledPackages` on Android 11+).
* **System Anti-Circumvention:**
  * Block factory reset from Settings (`DISALLOW_FACTORY_RESET`)
  * Lock date & time to automatic network time (`DISALLOW_CONFIG_DATE_TIME`)
  * Block Safe Boot (`DISALLOW_SAFE_BOOT`)
  * Block adding extra users or guest profiles (`DISALLOW_ADD_USER`)
  * Block sideloading from unknown sources (`DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`)
  * Optional strict restrictions on app control (`DISALLOW_APPS_CONTROL`), global app installs (`DISALLOW_INSTALL_APPS`), and USB debugging/developer options (`DISALLOW_DEBUGGING_FEATURES`)
* **One-Tap Protection Presets:** Switch between **Standard**, **Strict**, and **Lockdown** presets—or customize individual toggles—in **Settings**.
* **Critical System Safeguards (`Safety`):** Automatically excludes essential system components, default launcher, dialer, SMS app, active input methods (keyboards), package installer, permission controller, and Play Services from suspension.

### 4. Update-Safe Install Guard (`InstallGuard` & `InstallGuardActivity`)
* **Preserves Existing App Updates:** Enabling Install Guard snapshots all currently installed user apps into an allowlist so existing apps (like your banking app) continue updating normally via Google Play without interruption.
* **Quarantines New Installs:** Any newly installed non-allowed user package is hidden via `setApplicationHidden(..., true)` and flagged for review.
* **Approve or Silent Remove:** Review quarantined apps in **Installs** (`InstallGuardActivity`) to either **Approve** (un-hide and allow) or **Remove** (un-hide and silently uninstall via `PackageInstaller`).

### 5. PIN Access Control (`SecureActivity` & `PinGate`)
* Protects configuration screens behind a salted **SHA-256** PIN gate with automatic brute-force protection (5 failed attempts trigger a 5-minute lockout).

---

## Requirements

* **Android Version:** Android 8.0+ (`minSdk = 26`, `targetSdk = 37`)
* **Provisioning:** Device Owner privileges (typically set via ADB on a device with no existing accounts configured)
* **Permissions:**
  * `PACKAGE_USAGE_STATS` (Usage Access)
  * `QUERY_ALL_PACKAGES`
  * `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SPECIAL_USE`
  * `POST_NOTIFICATIONS`
  * `RECEIVE_BOOT_COMPLETED`
  * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

---

## Setup & Installation

### 1. Build & Install
Clone the repository, open it in **Android Studio**, sync Gradle, and install the debug or release APK onto your target Android device:
```bash
git clone https://github.com/ALIYUsbly/ScreenTimeManager.git
cd ScreenTimeManager
./gradlew installDebug
```

### 2. Provision as Device Owner via ADB
> **Note:** Android requires all Google/work accounts to be removed from the device before running `set-device-owner` via ADB (you can re-add your accounts afterward).

Connect the device with **USB Debugging** enabled and run:
```bash
adb shell dpm set-device-owner com.aliyusbly.screentimemanager/.ScreenTimeAdminReceiver
```

### 3. Complete In-App Setup
1. Open **Screen Time Manager** on the device.
2. Tap **Grant Usage Access** and enable usage tracking for the app.
3. Open **Settings**:
   * Enable a **PIN** under **Access** so limits and hardening toggles cannot be modified without authorization.
   * Choose a **Protection level** (`Standard`, `Strict`, or `Lockdown`). *(Warning: `Lockdown` disables USB debugging and revokes ADB access until lowered.)*
   * Tap **Exempt from battery optimization** under **Reliability** so the OS does not throttle background enforcement.
   * Optionally open **Installs → Manage new app installs** to enable the Install Guard allowlist.

---

## Project Architecture

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/aliyusbly/screentimemanager/
│   ├── MainActivity.kt              # Dashboard, status card, quick focus lock, search & app list
│   ├── AppRuleActivity.kt           # Per-app limits, blocked days, schedules, sessions & manual lock
│   ├── BatchActivity.kt             # Bulk rule configuration across multiple apps
│   ├── InstallGuardActivity.kt      # UI for reviewing, approving, or removing new installs
│   ├── InstallGuard.kt              # Allowlist engine, package hiding & silent uninstall helper
│   ├── SettingsActivity.kt          # PIN setup, global bedtime, protection levels & DO release
│   ├── Components.kt                # Material 3 theme, SecureActivity, cards, steppers & time pickers
│   ├── Ui.kt                        # Shared UI & PIN gate helpers
│   ├── Model.kt                     # AppRule, TimeWindow, Clock utilities & JSON persistence
│   ├── Store.kt                     # SharedPreferences state store & salted SHA-256 PIN verification
│   ├── Enforcer.kt                  # Rule evaluation & DevicePolicyManager suspension loop
│   ├── EnforcementService.kt        # Foreground polling service, BootReceiver & Watchdog scheduler
│   ├── AppLimitWorker.kt            # Periodic 15-minute WorkManager fallback worker
│   ├── UsageEngine.kt               # Raw UsageEvents calculator & Safety protected-package set
│   ├── DeviceControl.kt             # DevicePolicyManager restrictions, hardening & DO release
│   └── ScreenTimeAdminReceiver.kt   # DeviceAdminReceiver entry point
└── res/
    └── xml/device_admin.xml         # Device admin policy metadata
```

---

## Releasing Device Owner

To remove all restrictions, unsuspend managed apps, stop background enforcement, and relinquish Device Owner status:
1. Open **Screen Time Manager → Settings → Danger zone**.
2. Tap **Release device owner** and confirm.

---

## Disclaimer

This application uses privileged Android Enterprise **Device Owner** APIs (`DevicePolicyManager`) designed for personal self-control and device management. Enabling strict or lockdown policies (such as disabling USB debugging or factory reset) restricts system recovery paths—always ensure you remember your configured PIN before enabling high-restriction presets.