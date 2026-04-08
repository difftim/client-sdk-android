/*
 * Copyright 2025-2026 LiveKit, Inc.
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

import io.livekit.android.ConnectOptions
import io.livekit.android.util.LKLog
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Wraps a [QuicTransport] with automatic WebSocket fallback.
 *
 * When QUIC fires [SignalTransport.Listener.onFailure] or
 * [SignalTransport.Listener.onClosed] **before** [SignalTransport.Listener.onOpen],
 * the wrapper closes QUIC, creates a [WebSocketTransport] with the same
 * connection arguments, and connects through it transparently.
 *
 * All listener callbacks report this wrapper as the `transport` reference,
 * so [io.livekit.android.room.SignalClient]'s `isActiveTransport()` checks
 * remain valid after a fallback.
 */
class QuicWithFallbackTransport(
    private val quicTransport: QuicTransport,
    private val websocketFactory: WebSocket.Factory,
) : SignalTransport {

    override val attemptId: Long get() = quicTransport.attemptId
    override val sendOnOpen: ByteString? get() = quicTransport.sendOnOpen

    private val callbackExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "QuicFallback-Callback-${quicTransport.attemptId}").apply { isDaemon = true }
        }

    private var active: SignalTransport = quicTransport
    private var hasOpened = false
    private var hasFallenBack = false

    private var connectUrl: String? = null
    private var connectToken: String? = null
    private var connectOptions: ConnectOptions? = null
    private var outerListener: SignalTransport.Listener? = null

    /**
     * The raw listener passed to the inner transports. Every callback is
     * re-dispatched onto [callbackExecutor] so that all state mutations
     * and [outerListener] invocations happen on the same single thread,
     * regardless of whether the active transport is QUIC or WebSocket.
     */
    private val innerListener = object : SignalTransport.Listener {
        override fun onOpen(transport: SignalTransport) = postCallback {
            hasOpened = true
            outerListener?.onOpen(this@QuicWithFallbackTransport)
        }

        override fun onMessage(transport: SignalTransport, message: ByteString) = postCallback {
            outerListener?.onMessage(this@QuicWithFallbackTransport, message)
        }

        override fun onFailure(transport: SignalTransport, t: Throwable, response: Response?) = postCallback {
            if (!hasOpened && !hasFallenBack && transport === quicTransport) {
                fallbackToWebSocket(t)
                return@postCallback
            }
            if (transport === quicTransport && hasFallenBack) {
                LKLog.d { "[transport] ignoring stale QUIC onFailure after fallback: ${t.message}" }
                return@postCallback
            }
            outerListener?.onFailure(this@QuicWithFallbackTransport, t, response)
        }

        override fun onClosed(transport: SignalTransport, code: Int, reason: String) = postCallback {
            if (!hasOpened && !hasFallenBack && transport === quicTransport) {
                fallbackToWebSocket(TtsignalException("QUIC closed before open: $reason"))
                return@postCallback
            }
            if (transport === quicTransport && hasFallenBack) {
                LKLog.d { "[transport] ignoring stale QUIC onClosed after fallback: $reason" }
                return@postCallback
            }
            outerListener?.onClosed(this@QuicWithFallbackTransport, code, reason)
        }

        override fun onClosing(transport: SignalTransport, code: Int, reason: String) = postCallback {
            if (transport === quicTransport && hasFallenBack) {
                return@postCallback
            }
            outerListener?.onClosing(this@QuicWithFallbackTransport, code, reason)
        }

        override fun onRestarted(transport: SignalTransport, result: Int, address: String?) = postCallback {
            outerListener?.onRestarted(this@QuicWithFallbackTransport, result, address)
        }
    }

    /**
     * Called on [callbackExecutor]. Creates a [WebSocketTransport] and
     * connects it with the same arguments, replacing the active transport.
     */
    private fun fallbackToWebSocket(cause: Throwable) {
        hasFallenBack = true
        LKLog.w { "[transport] QUIC failed before open: ${cause.message}, falling back to WebSocket" }
        try {
            quicTransport.cancel()
        } catch (_: Exception) {
        }
        val ws = WebSocketTransport(attemptId, sendOnOpen, websocketFactory)
        active = ws
        ws.connect(connectUrl!!, connectToken!!, connectOptions!!, innerListener)
    }

    override fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        listener: SignalTransport.Listener,
    ) {
        connectUrl = url
        connectToken = token
        connectOptions = options
        outerListener = listener
        quicTransport.connect(url, token, options, innerListener)
    }

    override fun send(data: ByteString): Boolean = active.send(data)

    override fun close(code: Int, reason: String) = active.close(code, reason)

    override fun cancel() = active.cancel()

    override fun restart(networkHandle: Long) {
        if (hasFallenBack) {
            LKLog.i { "[transport] QUIC restart skipped: already fallen back to WebSocket" }
            postCallback { outerListener?.onRestarted(this, -1, null) }
            return
        }
        active.restart(networkHandle)
    }

    private fun postCallback(block: () -> Unit) {
        try {
            if (!callbackExecutor.isShutdown) {
                callbackExecutor.execute(block)
            }
        } catch (e: RejectedExecutionException) {
            LKLog.w(e) { "[transport] failed to post callback on fallback thread" }
        }
    }

    override fun toString(): String {
        return "${javaClass.simpleName}@${Integer.toHexString(hashCode())}(attemptId=$attemptId, active=${active.javaClass.simpleName})"
    }
}
