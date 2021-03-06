package com.example.rccontroller

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import debug
import org.json.JSONObject
import org.json.JSONTokener
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.*

object Channel {
    private var socket: Socket? = null
    private var btsocket: BluetoothSocket? = null
    private lateinit var socketWriter: OutputStream
    private lateinit var socketReader: Scanner

    private val dataTable = JSONObject()

    var isConnectionActive: Boolean = false
    lateinit var errorMessage: String

    private var setPassworCallback: (Boolean) -> Unit = { }

    private const val uuid: String = "94f39d29-7d6d-583a-973b-fba39e49d4ee"
    private const val DEVICE_NOT_AVAILABLE =
        "The device discovered is no longer available, please restart the discovery!"
    private const val ACCESS_DENIED =
        "ACCESS DENIED! Available tries left: "
    private const val NEWORK_EXCEPTION =
        "Something came up during the connection! Please check if the device is still available, and restart the discovery!"
    private const val ACCESS_FINAL_DENIAL =
        "You provided the wrong pin three times! Please make sure you try to connect ot the correct device, and restart the discovery."
    private const val DISTANCE = "distance"
    private const val SPEED = "speed"
    private val POWEROFF_MESSAGE = "POWEROFF".toByteArray(Charset.defaultCharset())
    private const val MODIFY_REQUEST = "modify"
    private val UPDATE_REQUEST = "update".toByteArray(Charset.defaultCharset())
    private const val OLD_VERSION = "old_version"
    private const val LATEST_VERSION = "latest_version"
    private const val GRANTED = "granted"
    private const val REJECTED = "rejected"
    private const val FINAL_REJECTION = "final_rejection"
    private var tries = 2
    private var oldVersion: String? = null
    private var latestVersion: String? = null

    private fun toHexString(hash: ByteArray?): String {
        val number = BigInteger(1, hash)
        val hexString = StringBuilder(number.toString(16))

        while (hexString.length < 32) hexString.insert(0, '0')

        return hexString.toString()
    }

    fun connect(
        host: String?,
        port: Int,
        bluetoothDevice: BluetoothDevice?,
        password: String,
        callback: (Boolean, Boolean) -> Unit
    ) {

        var result = true
        var fatalError = false

        try {
            if (!isConnectionActive) {
                if (host != null && port > 0) {
                    socket = Socket(host, port)
                    socket?.let { notNullableSocket ->
                        socketWriter = notNullableSocket.getOutputStream()
                        socketReader = Scanner(notNullableSocket.getInputStream())
                        isConnectionActive = true
                    }
                } else if (bluetoothDevice != null) {
                    btsocket =
                        bluetoothDevice.createRfcommSocketToServiceRecord(UUID.fromString(uuid))
                    btsocket?.let { notNullableSocket ->
                        notNullableSocket.connect()
                        socketWriter = notNullableSocket.outputStream
                        socketReader = Scanner(notNullableSocket.inputStream)
                        isConnectionActive = true
                    }
                } else {
                    errorMessage = DEVICE_NOT_AVAILABLE
                    fatalError = true
                }
            }

            socketWriter.write(
                toHexString(
                    MessageDigest.getInstance("SHA-256").digest(
                        password.toByteArray(Charset.defaultCharset())
                    )
                ).toByteArray(Charset.defaultCharset())
            )

            when (socketReader.nextLine()) {
                REJECTED -> {
                    errorMessage = ACCESS_DENIED + tries--
                    result = false
                }
                FINAL_REJECTION -> {
                    errorMessage = ACCESS_FINAL_DENIAL
                    tries = 2
                    result = false
                    fatalError = true
                    isConnectionActive = false
                }
                GRANTED -> {
                    result = true
                }
            }
        } catch (e: Exception) {
            errorMessage = NEWORK_EXCEPTION
            debug { errorMessage }
            result = false
            fatalError = true
            isConnectionActive = false
        }
        callback(result, fatalError)
    }

    fun setMessage(key: String, value: Boolean) {
        try {
            dataTable.put(key, value)
            socketWriter.write(dataTable.toString().toByteArray(Charset.defaultCharset()))
        } catch (e: Exception) {
            debug { e.message!! }
        }
    }

    fun setPasswordChangeRequest(
        name: String,
        oldPassword: String,
        newPassword: String,
        callback: (Boolean) -> Unit
    ) {
        setPassworCallback = callback

        val data = JSONObject()
        data.put(MODIFY_REQUEST, true)
        if (name != "") data.put("name", name)
        if (newPassword != "") data.put(
            "new_password", toHexString(
                MessageDigest.getInstance("SHA-256").digest(
                    newPassword.toByteArray(Charset.defaultCharset())
                )
            )
        )

        data.put(
            "old_password", toHexString(
                MessageDigest.getInstance("SHA-256").digest(
                    oldPassword.toByteArray(Charset.defaultCharset())
                )
            )
        )

        try {
            socketWriter.write(data.toString().toByteArray(Charset.defaultCharset()))
        } catch (e: Exception) {
            println(e.message)
        }
    }

    fun getBoolean(key: String): Boolean {
        var value = false
        try {
            value = dataTable.optBoolean(key, false)
        } catch (e: Exception) {
            println(e.message)
        }
        return value
    }

    fun getDouble(key: String, fallback: Double): Double {
        var value = fallback
        try {
            value = dataTable.optDouble(key, fallback)
        } catch (e: Exception) {
            println(e.message)
        }
        return value
    }

    fun run(callback: (String, String) -> Unit) {
        while (isConnectionActive) {
            try {
                if (oldVersion != null && latestVersion != null && oldVersion != latestVersion) {
                    callback(oldVersion!!, latestVersion!!)
                    oldVersion = null
                    latestVersion = null
                }
                val received = (JSONTokener(socketReader.nextLine()).nextValue() as JSONObject)

                received.keys().forEach { key ->
                    when (key) {
                        DISTANCE, SPEED -> dataTable.put(key, received.getDouble(key))
                        MODIFY_REQUEST -> setPassworCallback(received.getBoolean(key))
                        OLD_VERSION -> oldVersion = received.getString(key)
                        LATEST_VERSION -> latestVersion = received.getString(key)
                        else -> dataTable.put(key, received.getBoolean(key))
                    }
                }
            }
            catch (e: Exception) {
                println(e.message)
            }
        }
    }

    fun stop() {
        isConnectionActive = false
        socket?.close()
        btsocket?.close()
    }

    fun sendTurnOffSignal() {
        socketWriter.write(POWEROFF_MESSAGE)
    }

    fun sendUpdateRequest() {
        socketWriter.write(UPDATE_REQUEST)
    }
}
