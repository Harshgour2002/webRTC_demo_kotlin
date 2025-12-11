package com.example.webrtc_demo

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var btnConnectServer: Button
    private lateinit var btnCreateOffer: Button
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView
    private lateinit var etRemoteId: EditText
    private lateinit var etMessage: EditText
    private lateinit var tvChat: TextView


    private lateinit var signalClient: SignalClient
    private lateinit var rtcManager: WebRTCManager


    private var myId: String? = null
    private val signalingUrl = "wss://limitedly-hysterogenic-carla.ngrok-free.dev/ws/signal"



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        btnConnectServer = findViewById(R.id.btnConnectServer)
        etRemoteId = findViewById(R.id.etRemoteId)
        etMessage = findViewById(R.id.etMessage)
        tvChat = findViewById(R.id.tvChat)
        btnCreateOffer = findViewById(R.id.btnCreateOffer)
        btnSend = findViewById(R.id.btnSend)
        tvStatus = findViewById(R.id.tvStatus)


        rtcManager = WebRTCManager(this) { log -> runOnUiThread { tvChat.append(log + "\n") } }


        btnConnectServer.setOnClickListener {
            startSignaling()
        }


        btnCreateOffer.setOnClickListener {
            val to = etRemoteId.text.toString().trim()
            if (to.isEmpty()) return@setOnClickListener
            rtcManager.createPeerAndOffer(to)
        }


        btnSend.setOnClickListener {
            val msg = etMessage.text.toString()
            rtcManager.sendMessage(msg)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        signalClient.close()
        rtcManager.close()
    }


    private fun startSignaling() {
        tvStatus.text = "Connecting to server..."


// create signal client
        signalClient = SignalClient(signalingUrl)
        signalClient.connect(
            onMessage = { text ->
                Log.d("SignalClient", "recv: $text")
                runOnUiThread { handleSignalMessage(text) }
            },
            onFailure = {
                runOnUiThread { showToast("Failed to connect to signaling server") }
            }
        )


// give the signal client to rtc manager
        rtcManager.setSignalClient(signalClient)


        tvStatus.text = "Connected (awaiting welcome)"
    }


    private fun handleSignalMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            when (type) {
                "welcome" -> {
                    myId = json.getString("id")
                    tvStatus.text = "Connected as: $myId"
                    rtcManager.myId = myId
                }
                "offer" -> {
                    val from = json.getString("from")
                    val sdp = json.getString("sdp")
                    rtcManager.onOffer(from, sdp)
                }
                "answer" -> {
                    val sdp = json.getString("sdp")
                    rtcManager.onAnswer(sdp)
                }
                "candidate" -> {
                    val cand = json.getString("candidate")
                    val sdpMid = json.getString("sdpMid")
                    val sdpMLineIndex = json.getInt("sdpMLineIndex")
                    rtcManager.onIceCandidate(
                        JSONObject().apply {
                            put("candidate", cand)
                            put("sdpMid", sdpMid)
                            put("sdpMLineIndex", sdpMLineIndex)
                        }
                    )

                }
                "error" -> {
                    showToast(json.optString("message"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
