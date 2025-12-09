package com.example.webrtc_demo

import okhttp3.*
import java.util.concurrent.TimeUnit
import org.json.JSONObject


class SignalClient(private val url: String) {


    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()


    private val request = Request.Builder().url(url).build()
    private var ws: WebSocket? = null


    fun connect(onMessage: (String) -> Unit) {
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
// connected
            }


            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }


            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                t.printStackTrace()
            }
        })
    }


    fun send(to: String, data: JSONObject) {
// package: { to: "<id>", from: "<id (optional)>", type: "offer|answer|candidate", ... }
        val out = JSONObject()
        out.put("to", to)
// merge data
        val keys = data.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.put(k, data.get(k))
        }
        ws?.send(out.toString())
    }
}