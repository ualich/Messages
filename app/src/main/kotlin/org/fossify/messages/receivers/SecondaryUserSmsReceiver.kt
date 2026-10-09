package org.fossify.messages.receivers

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.provider.Telephony
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages

/**
 * Notification-only receive path for secondary Android users.
 *
 * Android hands `SMS_DELIVER` exclusively to the default SMS app of the system (Owner) user, so
 * [SmsReceiver] never fires inside a secondary user. The Owner's default SMS app still writes the
 * message into the shared telephony provider, which this app reads when opened, so only the
 * receive-time notification is missing. `SMS_RECEIVED`, by contrast, reaches every running user that
 * is allowed to use SMS. This receiver listens to it and, when running as a non-system user in which
 * this app holds the SMS role, resolves the row the Owner's app wrote and posts the ordinary
 * received-message notification. It never inserts anything.
 *
 * In the Owner user, or when another app holds the SMS role in this user, it does nothing.
 */
class SecondaryUserSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val appContext = context.applicationContext
        if (appContext.getSystemService(UserManager::class.java).isSystemUser) return
        if (!appContext.holdsSmsRole()) return

        val pending = goAsync()
        ensureBackgroundThread {
            try {
                handleReceived(appContext, intent)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * `Telephony.Sms.getDefaultSmsPackage()` returns null inside a secondary user even when this app
     * is selected as the default there, so ask [RoleManager] (API 29+) and keep the package comparison
     * only as the fallback for older releases. Never treat a null default as "ours": another app may
     * hold the role in this user, and notifying alongside it would duplicate every message.
     */
    private fun Context.holdsSmsRole(): Boolean {
        val roleHeld = isQPlus() && getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        return roleHeld || Telephony.Sms.getDefaultSmsPackage(this) == packageName
    }

    private fun handleReceived(context: Context, intent: Intent) {
        // Same parsing and filtering as SmsReceiver.
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return

        val address = parts.last().originatingAddress.orEmpty()
        val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }
        if (address.isBlank() || !context.shouldNotify(address, body)) return

        val threadId = context.getThreadId(address)
        val messageId = findProviderMessageId(context, threadId, body) ?: NO_MESSAGE_ID

        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)
        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        refreshMessages()
        refreshConversations()
        context.showReceivedMessageNotification(
            messageId = messageId,
            isMms = false,
            address = address,
            senderName = senderName,
            body = body,
            threadId = threadId,
            bitmap = bitmap
        )
    }

    /** The same filters [SmsReceiver] applies before it notifies. */
    private fun Context.shouldNotify(address: String, body: String): Boolean {
        if (isMessageFilteredOut(this, body) || isNumberBlocked(address)) return false
        if (!baseConfig.blockUnknownNumbers) return true
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return SimpleContactsHelper(this).existsSync(address, privateCursor) != ContactLookupResult.NotFound
    }

    /**
     * The Owner's default SMS app writes the row; it usually lands before or within a few hundred
     * milliseconds of this broadcast. Poll briefly for it so the notification's mark-as-read and delete
     * actions target the real message. Returns null when it has not appeared in time.
     */
    private fun findProviderMessageId(context: Context, threadId: Long, body: String): Long? {
        val projection = arrayOf(Telephony.Sms._ID)
        val selection = "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} >= ?"
        val since = (System.currentTimeMillis() - LOOKBACK_MS).toString()
        val selectionArgs = arrayOf(threadId.toString(), body, since)
        val sortOrder = "${Telephony.Sms.DATE} DESC"

        repeat(LOOKUP_ATTEMPTS) { attempt ->
            var found: Long? = null
            context.queryCursor(
                uri = Telephony.Sms.Inbox.CONTENT_URI,
                projection = projection,
                selection = selection,
                selectionArgs = selectionArgs,
                sortOrder = sortOrder
            ) { cursor ->
                if (found == null) {
                    found = cursor.getLongValue(Telephony.Sms._ID)
                }
            }
            found?.let { return it }
            if (attempt < LOOKUP_ATTEMPTS - 1) Thread.sleep(LOOKUP_INTERVAL_MS)
        }
        return null
    }

    private companion object {
        const val NO_MESSAGE_ID = -1L
        const val LOOKBACK_MS = 60_000L
        const val LOOKUP_ATTEMPTS = 5
        const val LOOKUP_INTERVAL_MS = 600L
    }
}
