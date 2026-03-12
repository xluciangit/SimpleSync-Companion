# SimpleSync Companion

[![Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/xlucian)

An Android app that automatically backs up files from your phone to a self-hosted [Simple Sync Server](https://github.com/xluciangit/SimpleSyncServer). Configure sync folders, set scan intervals, and let the app handle uploads silently in the background.

---

## Features

- **Automatic background sync** — WorkManager schedules periodic scans and uploads even when the app is closed
- **SHA-256 deduplication** — files already on the server are skipped without re-uploading
- **Multiple sync folders** — each folder maps a local Android directory to a named folder on the server
- **Per-folder settings** — scan interval (15 min → 24 hours), include/exclude hidden dotfiles
- **Large file support** — files over 100 MB automatically use a direct LAN URL, bypassing Cloudflare's tunnel limit; jobs pause when away from home and resume automatically when the server is reachable again
- **Upload queue** — live view of all pending, uploading, completed, failed, and cancelled jobs with progress bar and real-time upload speed
- **Pause / Resume** — pause all uploads at any time; state persists across app restarts
- **Dark / light / system theme**
- **Notifications** — foreground service notification during scan and upload; summary notification on completion
- **Boot persistence** — scanning resumes automatically after device reboot

---

## Requirements

- Android 8.0 (API 26) or higher
- A running [Simple Sync Server](https://github.com/xluciangit/SimpleSyncServer) instance
- Network access to the server (local Wi-Fi or via Cloudflare Tunnel)

---

## Installation

### F-Droid

Search for **SimpleSync Companion** in the F-Droid app, or add the repository and install directly.

### GitHub Releases

Download the latest `SimpleSyncCompanion.apk` from the [Releases page](https://github.com/xluciangit/SimpleSync-Companion/releases) and install it. You may need to enable **Install from unknown sources** in Android Settings → Security.

### Build from Source

```bash
git clone https://github.com/xluciangit/SimpleSync-Companion.git
cd SimpleSync-Companion
./gradlew assembleRelease
```

---

## Setup

### 1. Get your API key

Log in to Simple Sync Server with your **user account** (not admin). Go to **Settings** and copy the API key shown there.

### 2. Connect the app

Open SimpleSync Companion. On first launch the connection screen appears:

- **Server URL** — the full URL of your server, e.g. `https://sync.yourdomain.com` or `http://192.168.1.100:3000`
- **API Key** — paste the key from Step 1
- Tap **Test Connection** to verify, then **Connect**

The app also fetches the Direct Upload URL from the server at this point, used automatically for large files.

### 3. Add a sync folder

Go to the **Folders** tab and tap **+**:

- **Display Name** — a label for your own reference, e.g. `Documents`
- **Server Folder Name** — the folder name on the server, e.g. `Documents`
- **Scan Interval** — how often to check for new files (15 min to 24 hours)
- **Include hidden files** — whether to sync dotfiles and dotfolders (off by default)
- Tap **Choose Folder** and pick a local folder from the Android file picker

The app immediately starts an initial scan after the folder is added.

---

## Folder Settings

Tap **⚙ Settings** on any folder card to open the folder settings sheet:

- Change the **Scan Interval**
- Toggle **Include hidden files and folders** — controls whether files and directories whose names start with `.` are synced
- **Remove Folder** — removes the sync configuration; files already on the server are not deleted

---

## Upload Queue

The **Queue** tab shows all upload jobs. Jobs are sorted with actively uploading files at the top, followed by pending, failed, cancelled, then completed.

Each job card shows:

- Filename, server folder, file size, and date queued
- Status badge: Pending / Uploading / Completed / Failed / Cancelled
- Progress bar, percentage, and live upload speed while uploading
- Error message if failed

### Action chips

| Chip | Action |
|------|--------|
| **Pause / Resume** | Pause or resume all uploads — persists across app restarts |
| **Retry Failed** | Reset all failed jobs to Pending and trigger upload |
| **Clear Completed** | Remove all completed jobs from the list |
| **Clear Cancelled** | Remove all cancelled jobs from the list |

Completed jobs are automatically removed from the list 60 seconds after finishing.

### Filter chips

Filter the list by: All · Pending · Completed · Failed · Cancelled

---

## Large File Uploads (over 100 MB)

Cloudflare Tunnel has a 100 MB request size limit. For larger files:

1. In Simple Sync Server, go to Admin → Settings and set the **Direct Upload URL** to your server's local address, e.g. `http://192.168.1.100:3000`
2. The Android app fetches this URL on login and stores it
3. Files over 100 MB are automatically routed to the direct URL when it is reachable
4. If the direct URL is unreachable (e.g. you are away from home), large file jobs stay **Pending** and resume automatically the next time you are on the same network

---

## How Upload Works

1. The scanner finds new or changed files by comparing `lastModified` and `fileSize` against the local database
2. Each new file gets an `UploadJob` (status: `PENDING`)
3. UploadWorker stages the file to internal cache, computes its **SHA-256 hash**, and asks the server if it already has that hash
4. If the server already has it, the job is marked `COMPLETED` immediately — no upload
5. If not, the file is uploaded via `POST /api/upload`
6. On success, the job is marked `COMPLETED` and the file is recorded in the local `tracked_files` table
7. On failure, the job is marked `FAILED` — use **Retry Failed** or tap Retry on the card

The local database is only updated after a successful upload or a confirmed server-side dedup hit, so interrupted uploads always retry safely.

---

## Permissions

| Permission | Reason |
|------------|--------|
| `INTERNET` | Upload files to server |
| `ACCESS_NETWORK_STATE` | Check connectivity before upload |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Run upload service in background (required Android 9+) |
| `WAKE_LOCK` | Keep device awake during upload |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent Android from killing background uploads |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule scanning after reboot |
| `POST_NOTIFICATIONS` | Show upload progress and completion notifications (Android 13+) |

---

## Privacy

- No analytics, no tracking, no third-party services
- All data goes directly between your phone and your own server
- No account required beyond your self-hosted server credentials

---

## Building for Release

1. Generate a signing keystore if you don't have one:

```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias simplesync -keyalg RSA -keysize 2048 -validity 10000
```

2. Create a `local.properties` file in the project root (this file is gitignored):

```properties
KEYSTORE_PATH=/path/to/release.keystore
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=simplesync
KEY_PASSWORD=your_key_password
```

3. Build:

```bash
./gradlew assembleRelease
```

The APK will be at `app/build/outputs/apk/release/app-release.apk`.

---

## License

MIT
