package org.zeravrix.kotatsu.extension.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.zeravrix.kotatsu.BuildConfig
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.trust.TrustExtension

internal class ExtensionInstallReceiver(
    private val listener: Listener,
    private val scope: CoroutineScope,
    private val trustExtension: TrustExtension,
) : BroadcastReceiver() {

    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    android.util.Log.e("ExtensionInstallReceiver", "Failed to handle $event", e)
                }
            }
        }
    }

    fun register(context: Context) {
        ContextCompat.registerReceiver(
            context,
            this,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(ACTION_EXTENSION_ADDED)
        addAction(ACTION_EXTENSION_REPLACED)
        addAction(ACTION_EXTENSION_REMOVED)
        addDataScheme("package")
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED, ACTION_EXTENSION_ADDED -> {
                if (isReplacing(intent)) return
                getPackageNameFromIntent(intent)?.let { events.trySend(Event.Installed(context, it)) }
            }
            Intent.ACTION_PACKAGE_REPLACED, ACTION_EXTENSION_REPLACED -> {
                getPackageNameFromIntent(intent)?.let { events.trySend(Event.Installed(context, it)) }
            }
            Intent.ACTION_PACKAGE_REMOVED, ACTION_EXTENSION_REMOVED -> {
                if (isReplacing(intent)) return
                getPackageNameFromIntent(intent)?.let { events.trySend(Event.Removed(it)) }
            }
        }
    }

    private fun isReplacing(intent: Intent): Boolean {
        return intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Installed -> {
                when (val ext = ExtensionLoader.loadExtensionFromPkgName(event.context, event.pkgName, trustExtension)) {
                    is Extension.Loaded -> listener.onExtensionLoaded(ext)
                    is Extension.NotLoaded -> listener.onExtensionNotLoaded(ext)
                    null -> {}
                }
            }
            is Event.Removed -> listener.onPackageUninstalled(event.pkgName)
        }
    }

    private fun getPackageNameFromIntent(intent: Intent?): String? {
        return intent?.data?.encodedSchemeSpecificPart
    }

    interface Listener {
        suspend fun onExtensionLoaded(extension: Extension.Loaded)
        suspend fun onExtensionNotLoaded(extension: Extension.NotLoaded)
        suspend fun onPackageUninstalled(pkgName: String)
    }

    private sealed interface Event {
        data class Installed(val context: Context, val pkgName: String) : Event
        data class Removed(val pkgName: String) : Event
    }

    companion object {
        private const val ACTION_EXTENSION_ADDED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_ADDED"
        private const val ACTION_EXTENSION_REPLACED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_REPLACED"
        private const val ACTION_EXTENSION_REMOVED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_REMOVED"

        fun notifyAdded(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_ADDED)
        }

        fun notifyReplaced(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REPLACED)
        }

        fun notifyRemoved(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REMOVED)
        }

        private fun notify(context: Context, pkgName: String, action: String) {
            Intent(action).apply {
                data = "package:$pkgName".toUri()
                `package` = context.packageName
                context.sendBroadcast(this)
            }
        }
    }
}
