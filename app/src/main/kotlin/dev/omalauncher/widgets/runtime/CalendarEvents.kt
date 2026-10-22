package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Upcoming personal and managed-profile calendar events for `source c = android.calendar`. */
class CalendarEvents(
    private val context: Context,
    private val askPermission: () -> Unit,
) {
    @Volatile
    private var latest: Value = result(permission = granted(), items = emptyList())

    fun value(): Value = latest

    /** Calendar changes and the permission activity both refresh the source while its face is visible. */
    fun updates(): Flow<Value> =
        callbackFlow {
            var observer: ContentObserver? = null

            // Registering a calendar observer is itself a provider/binder call and is denied
            // before READ_CALENDAR is granted. Do not let that expected denial escape the flow:
            // WidgetInstance retries a failed pushed source, so an exception here would spin
            // registrations until Android's binder quota kills the launcher process.
            fun observeIfGranted() {
                if (observer != null || !granted()) return
                val next =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }
                runCatching {
                    context.contentResolver.registerContentObserver(Instances.CONTENT_URI, true, next)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching {
                            context.contentResolver.registerContentObserver(Instances.ENTERPRISE_CONTENT_URI, true, next)
                        }
                    }
                    observer = next
                }.onFailure {
                    // Permission can be revoked between granted() and registration. Keep the
                    // source alive; the permission broadcast/ticker can try again later.
                    runCatching { context.contentResolver.unregisterContentObserver(next) }
                }
            }

            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        observeIfGranted()
                        trySend(Unit)
                    }
                }
            val filter = IntentFilter(CalendarPermissionActivity.ACTION_PERMISSION_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
            observeIfGranted()
            val ticker = launch {
                while (true) {
                    delay(5 * 60_000L)
                    trySend(Unit)
                }
            }
            trySend(Unit)
            awaitClose {
                ticker.cancel()
                context.unregisterReceiver(receiver)
                observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
            }
        }.conflate().map {
            withContext(Dispatchers.IO) {
                read().also { latest = it }
            }
        }.distinctUntilChanged()

    /** `calendar("allow")` requests Android permission; `calendar("open", ...)` opens one event. */
    fun control(args: List<Value>) {
        when (args.firstOrNull()?.toString()) {
            "allow" -> if (!granted()) askPermission()
            "open" -> open(args)
        }
    }

    private fun open(args: List<Value>) {
        if (args.size < 6) return
        val profile = args[1].toString()
        val eventId = args[2].toString().toLongOrNull() ?: return
        val begin = args[3].toString().toDoubleOrNull()?.times(1000)?.toLong() ?: return
        val end = args[4].toString().toDoubleOrNull()?.times(1000)?.toLong() ?: return
        val allDay = args[5].toString().toBooleanStrictOrNull() ?: false
        runCatching {
            if (profile == "work" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                CalendarContract.startViewCalendarEventInManagedProfile(
                    context,
                    eventId,
                    begin,
                    end,
                    allDay,
                    Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            } else {
                val intent =
                    Intent(Intent.ACTION_VIEW)
                        .setData(ContentUris.withAppendedId(Events.CONTENT_URI, eventId))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    private fun read(): Value {
        if (!granted()) return result(permission = false, items = emptyList())
        val start = System.currentTimeMillis()
        val end = start + DAYS_AHEAD * DAY_MS
        val personal = query(Instances.CONTENT_URI, start, end, "personal")
        val work =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                query(Instances.ENTERPRISE_CONTENT_URI, start, end, "work")
            } else {
                emptyList()
            }
        val items = (personal + work).sortedBy { it.begin }.take(MAX_EVENTS)
        return result(permission = true, items = items)
    }

    private data class Event(
        val begin: Long,
        val value: Value,
    )

    private fun query(base: Uri, start: Long, end: Long, profile: String): List<Event> =
        runCatching {
            val range = base.buildUpon().appendPath(start.toString()).appendPath(end.toString()).build()
            val projection =
                arrayOf(
                    Instances.EVENT_ID,
                    Events.TITLE,
                    Instances.BEGIN,
                    Instances.END,
                    Instances.START_DAY,
                    Events.ALL_DAY,
                    Events.EVENT_LOCATION,
                )
            context.contentResolver.query(range, projection, null, null, "${Instances.BEGIN} ASC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(Instances.EVENT_ID)
                val titleColumn = cursor.getColumnIndexOrThrow(Events.TITLE)
                val beginColumn = cursor.getColumnIndexOrThrow(Instances.BEGIN)
                val endColumn = cursor.getColumnIndexOrThrow(Instances.END)
                val dayColumn = cursor.getColumnIndexOrThrow(Instances.START_DAY)
                val allDayColumn = cursor.getColumnIndexOrThrow(Events.ALL_DAY)
                val locationColumn = cursor.getColumnIndexOrThrow(Events.EVENT_LOCATION)
                buildList {
                    while (cursor.moveToNext() && size < MAX_PER_PROFILE) {
                        val eventId = cursor.getLong(idColumn)
                        val begin = cursor.getLong(beginColumn)
                        val finish = cursor.getLong(endColumn)
                        val allDay = cursor.getInt(allDayColumn) != 0
                        val day = localDayMillis(cursor.getInt(dayColumn))
                        val item =
                            Obj(
                                mapOf(
                                    "key" to Str("$profile:$eventId:$begin"),
                                    "id" to Str(eventId.toString()),
                                    "profile" to Str(profile),
                                    "title" to Str(cursor.getString(titleColumn).orEmpty().ifBlank { "Untitled event" }),
                                    "begin" to Num(begin / 1000.0),
                                    "end" to Num(finish / 1000.0),
                                    "day" to Num(day / 1000.0),
                                    "allDay" to bool(allDay),
                                    "location" to Str(cursor.getString(locationColumn).orEmpty()),
                                ),
                            )
                        add(Event(begin, item))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())

    private fun granted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** START_DAY is local to the device, which avoids shifting all-day events by the UTC offset. */
    private fun localDayMillis(julianDay: Int) = LocalDate.ofEpochDay(julianDay - EPOCH_JULIAN_DAY).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun result(permission: Boolean, items: List<Event>): Value =
        Obj(
            mapOf(
                "permission" to bool(permission),
                "workSupported" to bool(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
                "count" to Num(items.size.toDouble()),
                "items" to Lst(items.map { it.value }),
            ),
        )

    companion object {
        private const val DAYS_AHEAD = 7
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val MAX_PER_PROFILE = 40
        private const val MAX_EVENTS = 60
        private const val EPOCH_JULIAN_DAY = 2_440_588L
    }
}
