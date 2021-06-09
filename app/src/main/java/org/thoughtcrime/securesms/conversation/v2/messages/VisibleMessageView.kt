package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import androidx.core.view.isVisible
import kotlinx.android.synthetic.main.view_visible_message.view.*
import network.loki.messenger.R
import org.session.libsession.messaging.contacts.Contact.ContactContext
import org.session.libsession.utilities.ViewUtil
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.loki.utilities.toDp
import org.thoughtcrime.securesms.util.DateUtils
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class VisibleMessageView : LinearLayout {
    private var dx = 0.0f
    private var previousTranslationX = 0.0f
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var longPressCallback: Runnable? = null

    companion object {
        const val swipeToReplyThreshold = 90.0f // dp
        const val longPressMovementTreshold = 10.0f // dp
        const val longPressDurationThreshold = 250.0f // ms
    }

    // region Lifecycle
    constructor(context: Context) : super(context) {
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initialize()
    }

    private fun initialize() {
        LayoutInflater.from(context).inflate(R.layout.view_visible_message, this)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isHapticFeedbackEnabled = true
    }
    // endregion

    // region Updating
    fun bind(message: MessageRecord, previous: MessageRecord?, next: MessageRecord?) {
        val sender = message.individualRecipient
        val senderSessionID = sender.address.serialize()
        val threadID = message.threadId
        val threadDB = DatabaseFactory.getThreadDatabase(context)
        val thread = threadDB.getRecipientForThreadId(threadID)
        val contactDB = DatabaseFactory.getSessionContactDatabase(context)
        val isGroupThread = (thread?.isGroupRecipient == true)
        // Show profile picture and sender name if this is a group thread AND
        // the message is incoming
        if (isGroupThread && !message.isOutgoing) {
            profilePictureContainer.visibility = View.VISIBLE
            profilePictureView.publicKey = senderSessionID
            // TODO: Set glide on the profile picture view and update it
            // TODO: Show crown if this is an open group and the user is a moderator; otherwise hide it
            senderNameTextView.visibility = View.VISIBLE
            val context = if (thread?.isOpenGroupRecipient == true) ContactContext.OPEN_GROUP else ContactContext.REGULAR
            senderNameTextView.text = contactDB.getContactWithSessionID(senderSessionID)?.displayName(context) ?: senderSessionID
        } else {
            profilePictureContainer.visibility = View.GONE
            senderNameTextView.visibility = View.GONE
        }
        // Date break
        val showDateBreak = (previous == null || !DateUtils.isSameDay(message.timestamp, previous.timestamp))
        dateBreakTextView.isVisible = showDateBreak
        dateBreakTextView.text = if (showDateBreak) DateUtils.getRelativeDate(context, Locale.getDefault(), message.timestamp) else ""
        // Timestamp
        messageTimestampTextView.text = DateUtils.getExtendedRelativeTimeSpanString(context, Locale.getDefault(), message.timestamp)
        // Margins
        val messageContentContainerLayoutParams = messageContentContainer.layoutParams as LinearLayout.LayoutParams
        if (isGroupThread) {
            messageContentContainerLayoutParams.leftMargin = if (message.isOutgoing) resources.getDimension(R.dimen.very_large_spacing).toInt() else 0
        } else {
            messageContentContainerLayoutParams.leftMargin = if (message.isOutgoing) resources.getDimension(R.dimen.very_large_spacing).toInt()
                else resources.getDimension(R.dimen.medium_spacing).toInt()
        }
        messageContentContainerLayoutParams.rightMargin = if (message.isOutgoing) resources.getDimension(R.dimen.medium_spacing).toInt()
            else resources.getDimension(R.dimen.very_large_spacing).toInt()
        messageContentContainer.layoutParams = messageContentContainerLayoutParams
        // Set inter-message spacing
        val isStartOfMessageCluster = isStartOfMessageCluster(message, previous, isGroupThread)
        val isEndOfMessageCluster = isEndOfMessageCluster(message, next, isGroupThread)
        setMessageSpacing(isStartOfMessageCluster, isEndOfMessageCluster)
        // Gravity
        val gravity = if (message.isOutgoing) Gravity.RIGHT else Gravity.LEFT
        mainContainer.gravity = gravity or Gravity.BOTTOM
        // Populate content view
        messageContentView.bind(message, isStartOfMessageCluster, isEndOfMessageCluster)
    }

    private fun setMessageSpacing(isStartOfMessageCluster: Boolean, isEndOfMessageCluster: Boolean) {
        val topPadding = if (isStartOfMessageCluster) R.dimen.conversation_vertical_message_spacing_default else R.dimen.conversation_vertical_message_spacing_collapse
        ViewUtil.setPaddingTop(this, resources.getDimension(topPadding).roundToInt())
        val bottomPadding = if (isEndOfMessageCluster) R.dimen.conversation_vertical_message_spacing_default else R.dimen.conversation_vertical_message_spacing_collapse
        ViewUtil.setPaddingBottom(this, resources.getDimension(bottomPadding).roundToInt())
    }

    private fun isStartOfMessageCluster(current: MessageRecord, previous: MessageRecord?, isGroupThread: Boolean): Boolean {
        return if (isGroupThread) {
            previous == null || previous.isUpdate || !DateUtils.isSameDay(current.timestamp, previous.timestamp)
                || current.recipient.address != previous.recipient.address
        } else {
            previous == null || previous.isUpdate || !DateUtils.isSameDay(current.timestamp, previous.timestamp)
                || current.isOutgoing != previous.isOutgoing
        }
    }

    private fun isEndOfMessageCluster(current: MessageRecord, next: MessageRecord?, isGroupThread: Boolean): Boolean {
        return if (isGroupThread) {
            next == null || next.isUpdate || !DateUtils.isSameDay(current.timestamp, next.timestamp)
                || current.recipient.address != next.recipient.address
        } else {
            next == null || next.isUpdate || !DateUtils.isSameDay(current.timestamp, next.timestamp)
                || current.isOutgoing != next.isOutgoing
        }
    }

    fun recycle() {
        profilePictureView.recycle()
        messageContentView.recycle()
    }
    // endregion

    // region Interaction
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onFinish(event)
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        dx = x - event.rawX
        val oldLongPressCallback = longPressCallback
        if (oldLongPressCallback != null) {
            gestureHandler.removeCallbacks(oldLongPressCallback)
        }
        val longPressCallback = Runnable { onLongPress() }
        this.longPressCallback = longPressCallback
        gestureHandler.postDelayed(longPressCallback, VisibleMessageView.longPressDurationThreshold)
    }

    private fun onMove(event: MotionEvent) {
        val translationX = toDp(event.rawX + dx, context.resources)
        if (abs(translationX) < VisibleMessageView.longPressMovementTreshold) {
            return
        } else {
            val longPressCallback = longPressCallback
            if (longPressCallback != null) {
                gestureHandler.removeCallbacks(longPressCallback)
            }
        }
        // The idea here is to asymptotically approach a maximum drag distance
        val damping = 50.0f
        val sign = -1.0f
        val x = (damping * (sqrt(abs(translationX)) / sqrt(damping))) * sign
        this.translationX = x
        if (abs(x) > VisibleMessageView.swipeToReplyThreshold && abs(previousTranslationX) < VisibleMessageView.swipeToReplyThreshold) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            } else {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
        previousTranslationX = x
    }

    private fun onFinish(event: MotionEvent) {
        if (abs(translationX) > VisibleMessageView.swipeToReplyThreshold) {
            Log.d("Test", "Reply")
        }
        animate()
            .translationX(0.0f)
            .setDuration(150)
            .start()
    }

    private fun onLongPress() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Log.d("Test", "Long press")
    }
    // endregion
}