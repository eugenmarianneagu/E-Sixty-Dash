package com.bmw.carlauncher

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
            when (state) {
                Call.STATE_ACTIVE -> CallOverlayManager.onCallActive(this@BMWInCallService)
                Call.STATE_DISCONNECTED -> {
                    currentCall = null
                    listener?.onCallEnded()
                    CallOverlayManager.onCallEnded(this@BMWInCallService)
                }
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
            CallOverlayManager.onIncomingCall(this, number = number, name = name, forceShow = true)
        } else {
            listener?.onOutgoingCall(number, name)
            CallOverlayManager.onOutgoingCall(this, number = number, name = name, forceShow = true)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (currentCall == call) {
            currentCall = null
        }
        listener?.onCallEnded()
        CallOverlayManager.onCallEnded(this)
    }
}
