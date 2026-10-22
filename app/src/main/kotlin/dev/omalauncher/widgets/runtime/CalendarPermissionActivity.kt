package dev.omalauncher.widgets.runtime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** Small trampoline so a face can show Android's calendar permission prompt from its own button. */
class CalendarPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            finishRequest()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALENDAR) finishRequest()
    }

    private fun finishRequest() {
        sendBroadcast(Intent(ACTION_PERMISSION_CHANGED).setPackage(packageName))
        finish()
    }

    companion object {
        const val ACTION_PERMISSION_CHANGED = "dev.omalauncher.action.CALENDAR_PERMISSION_CHANGED"
        private const val REQUEST_CALENDAR = 7304
    }
}
