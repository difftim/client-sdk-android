/*
 * Copyright 2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room.transport

import android.net.Uri
import com.ttcall.smp.Config
import com.ttcall.smp.Connection
import com.ttcall.smp.Connector
import com.ttcall.smp.Const
import com.ttcall.smp.IConnectionHandler
import com.ttcall.smp.Stream
import io.livekit.android.ConnectOptions
import io.livekit.android.util.LKLog
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset

class QuicTransport(
    override val attemptId: Long,
    override val sendOnOpen: ByteString?,
    private val connector: Connector,
) : SignalTransport {
    private var connection: Connection? = null
    private var stream: Stream? = null
    private var listener: SignalTransport.Listener? = null

    companion object {
        init {
            try {
                System.loadLibrary("ttsignaljni")
            } catch (e: UnsatisfiedLinkError) {
                LKLog.e(e) { "Failed to load ttsignaljni library." }
            }
        }
    }

    override fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        listener: SignalTransport.Listener,
    ) {
        this.listener = listener
        val uri = Uri.parse(url)

        val config = Config().apply {
            idleTimeOut = 20000
            hostname = uri.host
            port = if (uri.port != -1) uri.port else 443
            maxConnections = 1
            congestCtrl = Const.CC_BBR2
            pingOn = true
            alpn = "h3"
        }

        val connectionHandler = object : IConnectionHandler {
            override fun onConnectResult(conn: Connection?, errorCode: Int, message: String?) {
                LKLog.v { "Ttsignal onConnectResult: $errorCode, $message" }
                if (errorCode == 0) {
                    this@QuicTransport.connection = conn
                    listener.onOpen(this@QuicTransport)
                } else {
                    listener.onFailure(this@QuicTransport, TtsignalException("Connection failed: $message", errorCode), null)
                }
            }

            override fun onStreamCreated(conn: Connection?, stream: Stream) {
                LKLog.v { "Ttsignal onStreamCreated: ${stream.id()}" }
                this@QuicTransport.stream = stream
                sendOnOpen?.let { send(it) }
            }

            override fun onStreamClosed(conn: Connection?, stream: Stream) {
                LKLog.v { "Ttsignal onStreamClosed: ${stream.id()}" }
                if (this@QuicTransport.stream?.id() == stream.id()) {
                    this@QuicTransport.stream = null
                }
            }

            override fun onRecvCmd(conn: Connection?, timestamp: Long, transId: Int, stream: Stream?, buffer: ByteArray) {
                val cmd = Charset.forName("UTF-8").decode(ByteBuffer.wrap(buffer)).toString()
                LKLog.v { "CLI command content: $cmd" }
            }

            override fun onRecvData(conn: Connection?, timestamp: Long, transId: Int, stream: Stream?, buffer: ByteArray) {
                LKLog.v { "Ttsignal onRecvData" }
                listener.onMessage(this@QuicTransport, buffer.toByteString())
            }

            override fun onClosed(conn: Connection?, reason: String?) {
                LKLog.v { "Ttsignal onClosed: $reason" }
                listener.onClosed(this@QuicTransport, 1000, reason ?: "Connection closed")
                cleanup()
            }

            override fun onException(conn: Connection?, errorMsg: String?) {
                LKLog.e { "Ttsignal onException: $errorMsg" }
                listener.onFailure(this@QuicTransport, TtsignalException(errorMsg ?: "Unknown ttsignal exception"), null)
                cleanup()
            }
        }

        this.connection = connector.createConnection(config, connectionHandler)

        // The example auth string is a JSON object. We'll replicate that.
        val authString = if (token.isNotEmpty()) {
            "{\"Authorization\":\"Bearer $token\"}"
        } else {
            ""
        }

        var connectUrl = url.replaceFirst("wss", "https")
        val items = connectUrl.split('?')
        if (items.size > 1) {
            connectUrl = items[0] + "?" + URLEncoder.encode(items[1], "UTF-8")
        }

        this.connection?.connect(connectUrl, authString)
    }

    override fun send(data: ByteString): Boolean {
        val stream = this.stream ?: return false.also { LKLog.w { "Ttsignal send called but stream is not available." } }
        val bytes = data.toByteArray()
        return stream.sendData(bytes) == 0
    }

    override fun close(code: Int, reason: String) {
        LKLog.v { "Ttsignal closing connection..." }
        connection?.close()
        cleanup()
    }

    override fun cancel() {
        LKLog.v { "Ttsignal cancelling connection..." }
        connection?.close()
        cleanup()
    }

    private fun cleanup() {
        connection = null
        stream = null
    }
}

class TtsignalException(message: String, val errorCode: Int? = null) : Exception(message)
