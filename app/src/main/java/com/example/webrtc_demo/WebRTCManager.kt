package com.example.webrtc_demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebRTCManager(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    var myId: String? = null
    private var signalClient: SignalClient? = null

    // WebRTC core
    private val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var remoteId: String? = null

    // cleanup guard
    private var isCleaningUp = false

    // single-thread executor for some WebRTC tasks if needed
    private val executor = Executors.newSingleThreadExecutor()

    // Buffer incoming ICE candidates until remote description set
    private val pendingRemoteIce = mutableListOf<JSONObject>()
    private val remoteDescSet = AtomicBoolean(false)

    // Queue outgoing messages until DataChannel opens
    private val pendingOutgoingMessages = mutableListOf<String>()

    // Handler for delayed cleanup
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Initialize PeerConnectionFactory safely and reuse eglBase
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, /* enableIntelVp8Encoder */ true, /* enableH264HighProfile */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun setSignalClient(client: SignalClient) {
        this.signalClient = client
    }

    private fun log(msg: String) {
        onLog(msg)
        Log.d("WebRTC", msg)
    }

    // --------------------------------------------------------------------------
    //  CREATE OFFER (Caller) - now waits for cleanup before creating new PC
    // --------------------------------------------------------------------------
    fun createPeerAndOffer(to: String) {
        log("createPeerAndOffer requested -> $to")
        remoteId = to

        // Ensure previous connection fully cleaned up before creating new one
        cleanupPeerConnection {
            // reset state
            pendingRemoteIce.clear()
            remoteDescSet.set(false)

            // create fresh peer connection and data channel
            createPeerConnection()
            createDataChannel()

            // create offer
            pc?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    log("Offer created (sdp length=${desc.description.length})")
                    pc?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            try {
                                val json = JSONObject()
                                json.put("type", "offer")
                                json.put("sdp", desc.description)
                                json.put("from", myId)
                                remoteId?.let { signalClient?.send(it, json) }
                                log("Offer sent to $remoteId")
                            } catch (e: Exception) {
                                log("Failed to send offer: ${e.message}")
                            }
                        }

                        override fun onSetFailure(p0: String?) { log("SetLocal failed: $p0") }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }

                override fun onCreateFailure(p0: String?) { log("Offer creation failed: $p0") }
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
        }
    }

    // --------------------------------------------------------------------------
    //  CREATE PEER CONNECTION
    // --------------------------------------------------------------------------
    private fun createPeerConnection() {
        // Add TURN servers for production / cross-network reliability
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            // Example TURN server (uncomment/replace for real testing):
            // PeerConnection.IceServer.builder("turn:your.turn.server:3478")
            //     .setUsername("user").setPassword("pass").createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.enableDscp = true

        log("Creating new PeerConnection")
        pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (isCleaningUp) {
                    log("Ignoring onIceCandidate during cleanup")
                    return
                }
                try {
                    log("ICE -> ${candidate.sdp} (mLineIndex=${candidate.sdpMLineIndex})")
                    val json = JSONObject()
                    json.put("type", "candidate")
                    json.put("candidate", candidate.sdp)
                    json.put("sdpMid", candidate.sdpMid)
                    json.put("sdpMLineIndex", candidate.sdpMLineIndex)
                    json.put("from", myId)
                    remoteId?.let { signalClient?.send(it, json) }
                } catch (e: Exception) {
                    log("Error sending ICE: ${e.message}")
                }
            }

            override fun onDataChannel(dc: DataChannel) {
                if (isCleaningUp) {
                    log("Ignoring onDataChannel during cleanup")
                    return
                }
                log("DataChannel Received (remote)")
                dataChannel = dc
                setupDataChannelCallbacks(dc)
            }

            override fun onRenegotiationNeeded() {
                if (isCleaningUp) return
                log("onRenegotiationNeeded called - ignoring (no-op).")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (isCleaningUp) {
                    log("Ignoring onConnectionChange during cleanup")
                    return
                }
                log("Connection: $newState")
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {
                // no-op
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (isCleaningUp) return
                log("ICE Connection: $newState")
            }

            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                if (isCleaningUp) return
                log("Signaling state: $p0")
            }

            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                if (isCleaningUp) return
                log("Ice gathering: $p0")
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
    }

    // --------------------------------------------------------------------------
    //  CREATE DATA CHANNEL (Offer side)
    // --------------------------------------------------------------------------
    private fun createDataChannel() {
        try {
            val init = DataChannel.Init()
            val dc = pc?.createDataChannel("chat", init)
            if (dc != null) {
                dataChannel = dc
                setupDataChannelCallbacks(dc)
                log("DataChannel created (local)")
            } else {
                log("createDataChannel returned null (pc may be null)")
            }
        } catch (e: Exception) {
            log("Failed to create data channel: ${e.message}")
        }
    }

    private fun setupDataChannelCallbacks(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {}

            override fun onStateChange() {
                val state = dc.state()
                log("DataChannel State = $state")
                if (state == DataChannel.State.OPEN) {
                    // flush queued outgoing messages
                    synchronized(pendingOutgoingMessages) {
                        if (pendingOutgoingMessages.isNotEmpty()) {
                            log("Flushing ${pendingOutgoingMessages.size} queued messages")
                        }
                        val iter = ArrayList(pendingOutgoingMessages)
                        for (m in iter) {
                            try {
                                val buffer = ByteBuffer.wrap(m.toByteArray())
                                dc.send(DataChannel.Buffer(buffer, false))
                                log("Flushed queued message: $m")
                            } catch (e: Exception) {
                                log("Failed to flush queued message: ${e.message}")
                            }
                        }
                        pendingOutgoingMessages.clear()
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    log("Received: " + String(bytes))
                } catch (e: Exception) {
                    log("Error reading DataChannel message: ${e.message}")
                }
            }
        })
    }

    // --------------------------------------------------------------------------
    //  HANDLE OFFER (Answer side)
    // --------------------------------------------------------------------------
    fun onOffer(from: String, sdp: String) {
        log("Received Offer from $from")

        if (myId == null) {
            log("Ignoring offer, myId is not set yet.")
            return
        }

        // Glare handling
        if (pc != null) {
            log("Glare condition detected. My ID: ${myId}, From ID: $from")
            if (myId!! > from) {
                log("Glare resolved: I am the impolite peer, ignoring incoming offer.")
                return
            } else {
                log("Glare resolved: I am the polite peer, closing my PeerConnection and accepting remote offer.")
                cleanupPeerConnection { /* continue below after cleanup */ }
                // we proceed to create a fresh PC below — but ensure previous cleanup finished
                // to avoid race we schedule the rest on the main thread shortly
                mainHandler.postDelayed({
                    doAcceptOffer(from, sdp)
                }, 300)
                return
            }
        }

        doAcceptOffer(from, sdp)
    }

    private fun doAcceptOffer(from: String, sdp: String) {
        remoteId = from
        pendingRemoteIce.clear()
        remoteDescSet.set(false)

        createPeerConnection()

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        try {
            pc?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    log("Remote description set successfully for offer.")
                    remoteDescSet.set(true)
                    drainPendingRemoteIce()
                    createAnswer()
                }

                override fun onSetFailure(p0: String?) { log("Failed to set remote description for offer: $p0") }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, offer)
        } catch (e: Exception) {
            log("Exception while setting remote description: ${e.message}")
        }
    }

    // --------------------------------------------------------------------------
    //  CREATE ANSWER
    // --------------------------------------------------------------------------
    private fun createAnswer() {
        try {
            pc?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    try {
                        pc?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                try {
                                    val json = JSONObject()
                                    json.put("type", "answer")
                                    json.put("sdp", desc.description)
                                    json.put("from", myId)
                                    remoteId?.let { signalClient?.send(it, json) }
                                    log("Answer sent")
                                } catch (e: Exception) {
                                    log("Failed to send answer: ${e.message}")
                                }
                            }

                            override fun onSetFailure(p0: String?) { log("setLocalDescription failed for answer: $p0") }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, desc)
                    } catch (e: Exception) {
                        log("Exception in onCreateSuccess(answer): ${e.message}")
                    }
                }

                override fun onCreateFailure(p0: String?) { log("Answer failed: $p0") }
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
        } catch (e: Exception) {
            log("createAnswer exception: ${e.message}")
        }
    }

    // --------------------------------------------------------------------------
    //  HANDLE ANSWER
    // --------------------------------------------------------------------------
    fun onAnswer(sdp: String) {
        log("Received Answer")
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        try {
            pc?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    log("Answer set OK")
                    remoteDescSet.set(true)
                    drainPendingRemoteIce()
                }

                override fun onSetFailure(p0: String?) { log("Answer set failed: $p0") }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, answer)
        } catch (e: Exception) {
            log("Error setting remote answer: ${e.message}")
        }
    }

    // --------------------------------------------------------------------------
    //  HANDLE ICE CANDIDATE (incoming from remote)
    // --------------------------------------------------------------------------
    fun onIceCandidate(json: JSONObject) {
        try {
            // if pc not created or remote desc not set yet, buffer candidate
            if (pc == null || !remoteDescSet.get()) {
                log("PC not ready or remote desc not set, buffering ICE")
                pendingRemoteIce.add(JSONObject(json.toString()))
                return
            }
            val candidate = IceCandidate(
                json.getString("sdpMid"),
                json.getInt("sdpMLineIndex"),
                json.getString("candidate")
            )
            log("Received ICE candidate - adding to pc")
            pc?.addIceCandidate(candidate)
        } catch (e: Exception) {
            log("Error adding ICE candidate: ${e.message}")
        }
    }

    private fun drainPendingRemoteIce() {
        if (pendingRemoteIce.isEmpty()) return
        log("Draining ${pendingRemoteIce.size} buffered ICE candidates")
        val copy = ArrayList(pendingRemoteIce)
        for (j in copy) {
            try {
                val candidate = IceCandidate(
                    j.getString("sdpMid"),
                    j.getInt("sdpMLineIndex"),
                    j.getString("candidate")
                )
                pc?.addIceCandidate(candidate)
            } catch (e: Exception) {
                log("Failed to add buffered ICE: ${e.message}")
            }
        }
        pendingRemoteIce.clear()
    }

    // --------------------------------------------------------------------------
    // SEND MESSAGE - queues until datachannel open
    // --------------------------------------------------------------------------
    fun sendMessage(msg: String) {
        try {
            val dc = dataChannel
            if (dc?.state() == DataChannel.State.OPEN) {
                val buffer = ByteBuffer.wrap(msg.toByteArray())
                dc.send(DataChannel.Buffer(buffer, false))
                log("Message Sent: $msg")
            } else {
                synchronized(pendingOutgoingMessages) {
                    pendingOutgoingMessages.add(msg)
                }
                log("DataChannel not open, queued message (state=${dc?.state()})")
            }
        } catch (e: Exception) {
            log("Failed to send message: ${e.message}")
        }
    }

    // --------------------------------------------------------------------------
    // TEARDOWN / CLEANUP
    // --------------------------------------------------------------------------
    fun cleanupPeerConnection(onComplete: () -> Unit) {
        if (pc == null) {
            onComplete()
            return
        }

        if (isCleaningUp) {
            // if already cleaning up, call back eventually
            mainHandler.postDelayed({ onComplete() }, 300)
            return
        }

        isCleaningUp = true
        log("Starting cleanup of PeerConnection")

        try { dataChannel?.close() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}

        // Wait for WebRTC internal shutdown to finish and swallow callbacks during this time
        mainHandler.postDelayed({
            pc = null
            dataChannel = null
            pendingRemoteIce.clear()
            pendingOutgoingMessages.clear()
            remoteDescSet.set(false)
            isCleaningUp = false
            log("PeerConnection torn down")
            onComplete()
        }, 300)
    }

    fun close() {
        cleanupPeerConnection { /* done */ }
        try { factory.dispose() } catch (_: Exception) {}
        try { eglBase.release() } catch (_: Exception) {}
    }
}
