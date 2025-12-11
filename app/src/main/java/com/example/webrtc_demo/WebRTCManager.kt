package com.example.webrtc_demo

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class WebRTCManager(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    var myId: String? = null
    private var signalClient: SignalClient? = null

    private var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var remoteId: String? = null

    // single-thread executor for some WebRTC tasks if needed
    private val executor = Executors.newSingleThreadExecutor()

    init {
        // Initialize PeerConnectionFactory safely
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext, /* enableIntelVp8Encoder */ true, /* enableH264HighProfile */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

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
    //  CREATE OFFER (Caller)
    // --------------------------------------------------------------------------

    fun createPeerAndOffer(to: String) {
        log("Creating peer & offer")

        remoteId = to
        // If an existing PC exists, close and recreate (avoid stale state)
        try {
            pc?.close()
        } catch (e: Exception) {
            Log.w("WebRTC", "Error closing existing pc: ${e.message}")
        }
        pc = null
        dataChannel = null

        createPeerConnection()
        createDataChannel()

        pc?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                try {
                    pc?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            try {
                                val json = JSONObject()
                                json.put("type", "offer")
                                json.put("sdp", desc.description)
                                json.put("from", myId)
                                remoteId?.let { signalClient?.send(it, json) }
                                log("Offer sent to $to")
                            } catch (e: Exception) {
                                log("Failed to send offer: ${e.message}")
                            }
                        }
                        override fun onSetFailure(p0: String?) { log("setLocalDescription failed: $p0") }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                } catch (e: Exception) {
                    log("Exception in onCreateSuccess: ${e.message}")
                }
            }

            override fun onCreateFailure(p0: String?) { log("Offer failed: $p0") }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    // --------------------------------------------------------------------------
    //  CREATE PEER CONNECTION
    // --------------------------------------------------------------------------

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers)
        // tweak any config params if needed
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        pc = factory.createPeerConnection(config, object : PeerConnection.Observer {

            override fun onIceCandidate(candidate: IceCandidate) {
                try {
                    log("ICE -> ${candidate.sdp}")

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
                log("DataChannel Received (answer side)")
                dataChannel = dc
                setupDataChannelCallbacks(dc)
            }

            override fun onRenegotiationNeeded() {
                // safe no-op or you could optionally start a renegotiation:
                log("onRenegotiationNeeded called - ignoring (no-op).")
                // if you want automated renegotiation, createOffer() here with checks to avoid glare.
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                log("Connection: $newState")
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {
                // no-op
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                log("ICE Connection: $newState")
            }

            override fun onAddStream(stream: MediaStream?) {
                // deprecated in Unified-Plan; ignore
            }

            override fun onRemoveStream(p0: MediaStream?) {
                // no-op
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                log("Signaling state: $p0")
            }

            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                log("Ice gathering: $p0")
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
                // no-op
            }

            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
                // no-op
            }
        })
    }

    // --------------------------------------------------------------------------
    //  CREATE DATA CHANNEL (Offer side)
    // --------------------------------------------------------------------------

    private fun createDataChannel() {
        try {
            val init = DataChannel.Init()
            dataChannel = pc?.createDataChannel("chat", init)
            dataChannel?.let { setupDataChannelCallbacks(it) }
            log("DataChannel created")
        } catch (e: Exception) {
            log("Failed to create data channel: ${e.message}")
        }
    }

    private fun setupDataChannelCallbacks(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {}

            override fun onStateChange() {
                log("DataChannel State = ${dc.state()}")
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
                try { pc?.close() } catch (_: Exception) {}
                pc = null
                dataChannel = null
            }
        }

        remoteId = from

        createPeerConnection()

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        try {
            pc?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    log("Remote description set successfully for offer.")
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
                override fun onSetSuccess() { log("Answer set OK") }
                override fun onSetFailure(p0: String?) { log("Answer set failed: $p0") }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, answer)
        } catch (e: Exception) {
            log("Error setting remote answer: ${e.message}")
        }
    }

    // --------------------------------------------------------------------------
    //  HANDLE ICE CANDIDATE
    // --------------------------------------------------------------------------

    fun onIceCandidate(json: JSONObject) {
        try {
            val candidate = IceCandidate(
                json.getString("sdpMid"),
                json.getInt("sdpMLineIndex"),
                json.getString("candidate")
            )
            log("Received ICE candidate")
            pc?.addIceCandidate(candidate)
        } catch (e: Exception) {
            log("Error adding ICE candidate: ${e.message}")
        }
    }

    // --------------------------------------------------------------------------
    // SEND MESSAGE
    // --------------------------------------------------------------------------

    fun sendMessage(msg: String) {
        try {
            if (dataChannel?.state() == DataChannel.State.OPEN) {
                val buffer = ByteBuffer.wrap(msg.toByteArray())
                dataChannel?.send(DataChannel.Buffer(buffer, false))
                log("Message Sent: $msg")
            } else {
                log("DataChannel not open, cannot send message (state=${dataChannel?.state()})")
            }
        } catch (e: Exception) {
            log("Failed to send message: ${e.message}")
        }
    }

    fun close() {
        try { dataChannel?.close() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
    }
}
