package com.example.webrtc_demo

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalClient(private val url: String) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val request = Request.Builder().url(url).build()
    private var ws: WebSocket? = null

    fun connect(onMessage: (String) -> Unit, onFailure: () -> Unit) {
        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("SignalClient", "WebSocket Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("SignalClient", "Received: $text")
                onMessage(text)
            }



            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("SignalClient", "WebSocket Failure: ${t.message}")
                onFailure()
            }
        })
    }

    fun send(to: String, data: JSONObject) {
        try {
            val json = JSONObject()

            // IMPORTANT: Always include "to" + "from" + "type"
            json.put("to", to)
            json.put("from", data.optString("from"))
            json.put("type", data.getString("type"))

            // Now add the remaining fields safely
            val keys = data.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k != "type" && k != "from") {
                    json.put(k, data.get(k))
                }
            }

            Log.d("SignalClient", "Sending: $json")
            ws?.send(json.toString())

        } catch (e: Exception) {
            Log.e("SignalClient", "Error sending: ${e.message}")
        }
    }

    fun close() {
        ws?.close(1000, "Normal Closure")
    }
}
