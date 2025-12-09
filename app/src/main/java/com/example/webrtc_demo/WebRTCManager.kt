package com.example.webrtc_demo

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer

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

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
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
        createPeerConnection()
        createDataChannel()

        pc?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val json = JSONObject()
                        json.put("type", "offer")
                        json.put("sdp", desc.description)
                        json.put("from", myId)
                        signalClient?.send(to, json)
                        log("Offer sent to $to")
                    }
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
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

        pc = factory.createPeerConnection(config, object : PeerConnection.Observer {

            override fun onIceCandidate(candidate: IceCandidate) {
                log("ICE -> ${candidate.sdp}")

                val json = JSONObject()
                json.put("type", "candidate")
                json.put("candidate", candidate.sdp)
                json.put("sdpMid", candidate.sdpMid)
                json.put("sdpMLineIndex", candidate.sdpMLineIndex)
                json.put("from", myId)

                remoteId?.let { signalClient?.send(it, json) }
            }

            override fun onDataChannel(dc: DataChannel) {
                log("DataChannel Received (answer side)")
                dataChannel = dc
                setupDataChannelCallbacks(dc)
            }

            override fun onRenegotiationNeeded() {
                TODO("Not yet implemented")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                log("Connection: $newState")
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {
                TODO("Not yet implemented")
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                log("ICE Connection: $newState")
            }

            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {
                TODO("Not yet implemented")
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
    }

    // --------------------------------------------------------------------------
    //  CREATE DATA CHANNEL (Offer side)
    // --------------------------------------------------------------------------

    private fun createDataChannel() {
        val init = DataChannel.Init()
        dataChannel = pc?.createDataChannel("chat", init)
        dataChannel?.let { setupDataChannelCallbacks(it) }
        log("DataChannel created")
    }

    private fun setupDataChannelCallbacks(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {}

            override fun onStateChange() {
                log("DataChannel State = ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                log("Received: " + String(bytes))
            }
        })
    }

    // --------------------------------------------------------------------------
    //  HANDLE OFFER (Answer side)
    // --------------------------------------------------------------------------

    fun onOffer(from: String, sdp: String) {
        log("Received Offer from $from")
        remoteId = from

        createPeerConnection()

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                createAnswer()
            }
            override fun onSetFailure(p0: String?) { log("Remote offer failed") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, offer)
    }

    // --------------------------------------------------------------------------
    //  CREATE ANSWER
    // --------------------------------------------------------------------------

    private fun createAnswer() {
        pc?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val json = JSONObject()
                        json.put("type", "answer")
                        json.put("sdp", desc.description)
                        json.put("from", myId)
                        signalClient?.send(remoteId!!, json)
                        log("Answer sent")
                    }

                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }

            override fun onCreateFailure(p0: String?) { log("Answer failed: $p0") }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    // --------------------------------------------------------------------------
    //  HANDLE ANSWER
    // --------------------------------------------------------------------------

    fun onAnswer(sdp: String) {
        log("Received Answer")
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { log("Answer set OK") }
            override fun onSetFailure(p0: String?) { log("Answer set failed") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, answer)
    }

    // --------------------------------------------------------------------------
    //  HANDLE ICE CANDIDATE
    // --------------------------------------------------------------------------

    fun onIceCandidate(json: JSONObject) {
        val candidate = IceCandidate(
            json.getString("sdpMid"),
            json.getInt("sdpMLineIndex"),
            json.getString("candidate")
        )
        log("Received ICE candidate")
        pc?.addIceCandidate(candidate)
    }

    // --------------------------------------------------------------------------
    // SEND MESSAGE
    // --------------------------------------------------------------------------

    fun sendMessage(msg: String) {
        val buffer = ByteBuffer.wrap(msg.toByteArray())
        dataChannel?.send(DataChannel.Buffer(buffer, false))
        log("Message Sent: $msg")
    }

}
