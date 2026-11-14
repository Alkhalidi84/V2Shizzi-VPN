package com.fastnode.app

import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Thin bridge around the bundled libv2ray API.
 *
 * Access is synchronized because a stop request can arrive while a start is
 * creating its controller. The VPN service also serializes its lifecycle.
 */
object XrayCore {
    private val lock = Any()
    private var controller: Any? = null

    fun start(context: Context, config: String) {
        synchronized(lock) {
            stopLocked()
            try {
                val library = Class.forName("libv2ray.Libv2ray")
                runCatching {
                    library.getMethod("initCoreEnv", String::class.java, String::class.java)
                        .invoke(null, context.filesDir.absolutePath, "")
                }

                val callbackType = Class.forName("libv2ray.CoreCallbackHandler")
                val callback = Proxy.newProxyInstance(
                    callbackType.classLoader,
                    arrayOf(callbackType)
                ) { _, method, _ ->
                    when (method.returnType) {
                        java.lang.Long.TYPE -> 0L
                        java.lang.Integer.TYPE -> 0
                        java.lang.Boolean.TYPE -> false
                        else -> null
                    }
                }

                val instance = library.getMethod("newCoreController", callbackType)
                    .invoke(null, callback)
                    ?: throw IllegalStateException("Xray did not create a core controller")

                val startMethod = instance.javaClass.methods.firstOrNull { it.name == "startLoop" }
                    ?: throw IllegalStateException("Xray startLoop API is unavailable")

                when (startMethod.parameterTypes.size) {
                    2 -> startMethod.invoke(instance, config, 0)
                    1 -> startMethod.invoke(instance, config)
                    else -> throw IllegalStateException("Unsupported Xray startLoop signature")
                }
                controller = instance
            } catch (error: InvocationTargetException) {
                throw IllegalStateException(
                    error.targetException?.message ?: "Xray failed to start",
                    error.targetException ?: error
                )
            } catch (error: ReflectiveOperationException) {
                throw IllegalStateException("Xray library/API mismatch: ${error.message}", error)
            }
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val current = controller ?: return
        controller = null
        runCatching { current.javaClass.getMethod("stopLoop").invoke(current) }
    }
}
