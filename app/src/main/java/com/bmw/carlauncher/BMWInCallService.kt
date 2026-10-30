package com.bmw.carlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile

class BMWInCallService : InCallService() {

    companion object {
        var currentCall: Call? = null
        var listener: CallListener? = null

        interface CallListener {
            fun onIncomingCall(number: String?, name: String?)
            fun onOutgoingCall(number: String?, name: String?)
            fun onCallStateChanged(state: Int)
            fun onCallEnded()
        }

        fun answerCurrentCall(): Boolean {
            return try {
                currentCall?.let {
                    it.answer(VideoProfile.STATE_AUDIO_ONLY)
                    true
                } ?: false
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        fun rejectCurrentCall(): Boolean {
            return try {
                currentCall?.let {
                    it.reject(false, null)
                    true
                } ?: false
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        fun hangupCurrentCall(): Boolean {
            return try {
                currentCall?.let {
                    it.disconnect()
                    true
                } ?: false
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            listener?.onCallStateChanged(state)
            if (state == Call.STATE_DISCONNECTED) {
                currentCall = null
                listener?.onCallEnded()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        call.registerCallback(callCallback)

        val handle = call.details?.handle
        val number = handle?.schemeSpecificPart ?: call.details?.callerDisplayName
        val name = call.details?.callerDisplayName

        val isIncoming = call.state == Call.STATE_RINGING
        if (isIncoming) {
            listener?.onIncomingCall(number, name)
        } else {
            listener?.onOutgoingCall(number, name)
        }

        // Aduce launcher-ul in prim-plan cu overlay-ul activat si suprima ecranul dialerului nativ
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (isIncoming) {
                putExtra("EXTRA_INCOMING_CALL", true)
            } else {
                putExtra("EXTRA_OUTGOING_CALL", true)
            }
            putExtra("EXTRA_CALL_NUMBER", number)
            putExtra("EXTRA_CALL_NAME", name)
        }

        val handler = Handler(Looper.getMainLooper())
        val delays = longArrayOf(0L, 200L, 450L, 850L, 1400L)
        for (d in delays) {
            handler.postDelayed({
                if (currentCall != null) {
                    try {
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                        val targetTask = MainActivity.currentTaskId
                        if (targetTask != -1) {
                            am?.moveTaskToFront(targetTask, 0)
                        }
                    } catch (_: Exception) {}
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }, d)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (currentCall == call) {
            currentCall = null
        }
        listener?.onCallEnded()
    }
}
