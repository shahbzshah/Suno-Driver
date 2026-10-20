package com.megah.sunopoc

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Forced-update manager — Megah's rule: the app always runs the latest
 * version. On every launch it asks GitHub for the newest release of
 * shahbzshah/Suno-Driver (release tag "vN" where N is the versionCode,
 * same convention as the Photo Prompt Codes app). If the release is newer
 * than the installed build, a non-dismissible dialog gates the app until
 * the update is downloaded and installed.
 *
 * Safety valves, deliberately:
 *  - If the check itself fails (offline, GitHub down), the app opens
 *    normally — a failed check must never brick the app. The force only
 *    applies when a newer release is CONFIRMED.
 *  - If a download/install fails after a confirmed update, an
 *    "Open app anyway" escape appears alongside Retry, so a broken
 *    release can never lock Megah out of his own app.
 *
 * Installs use the framework PackageInstaller (no AndroidX FileProvider):
 * the APK is written into an install session and the system shows its
 * own confirmation prompt. Requires REQUEST_INSTALL_PACKAGES and the
 * one-time "allow installs from this app" system toggle.
 */
object UpdateManager {

    private const val LATEST_URL =
        "https://api.github.com/repos/shahbzshah/Suno-Driver/releases/latest"

    data class Release(val code: Int, val tag: String, val apkUrl: String)

    fun checkForForcedUpdate(activity: Activity, log: (String) -> Unit) {
        val installed = installedCode(activity)
        thread(name = "update-check") {
            val release = fetchLatest()
            activity.runOnUiThread {
                when {
                    release == null ->
                        log("Update: check failed (offline?) — continuing on v$installed")
                    release.code > installed -> {
                        log("Update: v${release.code} available, installed v$installed — update required")
                        showGate(activity, release, log)
                    }
                    else -> log("Update: you're on the latest (v$installed)")
                }
            }
        }
    }

    private fun installedCode(activity: Activity): Int =
        try {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
        } catch (e: Exception) {
            0
        }

    private fun fetchLatest(): Release? {
        return try {
            val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "SunoDriver-Android")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")
            val code = tag.removePrefix("v").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            val url = apkUrl ?: return null
            Release(code, tag, url)
        } catch (e: Exception) {
            null
        }
    }

    private fun showGate(activity: Activity, release: Release, log: (String) -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val status = TextView(activity).apply {
            text = "Suno Driver ${release.tag} is ready.\n" +
                "This app always runs the latest version — update to continue."
            textSize = 15f
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(status)
            addView(progress)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Update required")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Update now", null)
            .setNeutralButton("Open app anyway", null)
            .create()
        dialog.setOnShowListener {
            val updateBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val escapeBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            escapeBtn.visibility = View.GONE // appears only if an update attempt fails
            escapeBtn.setOnClickListener { dialog.dismiss() }
            updateBtn.setOnClickListener {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    status.text = "One-time permission needed: allow installs for " +
                        "Suno Driver on the next screen, come back, then tap Update now again."
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        status.text = "Couldn't open the install-permission screen: ${e.message}"
                    }
                    return@setOnClickListener
                }
                updateBtn.isEnabled = false
                escapeBtn.visibility = View.GONE
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = true
                status.text = "Downloading ${release.tag}…"
                download(
                    activity, release,
                    onProgress = { pct ->
                        progress.isIndeterminate = false
                        progress.progress = pct
                        status.text = "Downloading ${release.tag}… $pct%"
                    },
                    onDone = { ok, err ->
                        if (ok) {
                            status.text =
                                "Download complete — confirm the install on the system prompt."
                            log("Update: install prompt shown for ${release.tag}")
                            dialog.dismiss()
                        } else {
                            status.text =
                                "Update failed: $err\nCheck your connection and retry."
                            progress.visibility = View.GONE
                            updateBtn.isEnabled = true
                            updateBtn.text = "Retry"
                            escapeBtn.visibility = View.VISIBLE
                            log("Update: FAILED — $err")
                        }
                    }
                )
            }
        }
        dialog.show()
    }

    private fun download(
        activity: Activity,
        release: Release,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        thread(name = "update-download") {
            try {
                val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "SunoDriver-Android")
                conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong
                val outFile = File(activity.cacheDir, "suno-driver-update.apk")
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                activity.runOnUiThread { onProgress(pct) }
                            }
                        }
                    }
                }
                activity.runOnUiThread {
                    try {
                        install(activity, outFile)
                        onDone(true, "")
                    } catch (e: Exception) {
                        onDone(false, "install: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread { onDone(false, "download: ${e.message}") }
            }
        }
    }

    private fun install(activity: Activity, apk: File) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        apk.inputStream().use { input ->
            session.openWrite("package", 0, apk.length()).use { out ->
                input.copyTo(out)
                session.fsync(out)
            }
        }
        val intent = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pending = PendingIntent.getActivity(activity, sessionId, intent, flags)
        session.commit(pending.intentSender)
        session.close()
    }
}
