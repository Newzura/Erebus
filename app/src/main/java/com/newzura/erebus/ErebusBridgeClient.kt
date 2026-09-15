package com.newzura.erebus

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Client TCP pour se connecter au pont Erebus Bridge (ESP32-S3)
 * Permet la communication entre l'application Android et le matériel via Wi-Fi
 */
class ErebusBridgeClient {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isConnected = false
    private var readThread: Thread? = null

    companion object {
        private const val TAG = "ErebusBridge"
        const val DEFAULT_IP = "192.168.4.1" // IP par défaut de l'ESP32 en mode AP
        const val DEFAULT_PORT = 5288
    }

    interface MessageListener {
        fun onMessageReceived(message: String)
        fun onConnectionLost()
    }

    private var listener: MessageListener? = null

    fun setListener(listener: MessageListener?) {
        this.listener = listener
    }

    /**
     * Se connecte au serveur TCP du pont Erebus Bridge
     * @param ip Adresse IP du pont (par défaut: 192.168.4.1)
     * @param port Port TCP du pont (par défaut: 5288)
     */
    fun connect(ip: String = DEFAULT_IP, port: Int = DEFAULT_PORT) {
        if (isConnected) return

        thread {
            try {
                Log.i(TAG, "Tentative de connexion à $ip:$port...")
                socket = Socket(ip, port)
                socket?.soTimeout = 0 // Bloquant pour la lecture
                
                outputStream = socket!!.getOutputStream()
                inputStream = socket!!.getInputStream()
                isConnected = true
                
                Log.i(TAG, "✅ Connecté au Bridge Erebus !")
                
                // Lancer thread de lecture
                readThread = thread {
                    try {
                        val buffer = ByteArray(1024)
                        while (isConnected && inputStream != null) {
                            val bytesRead = inputStream!!.read(buffer)
                            if (bytesRead > 0) {
                                val msg = String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
                                Log.d(TAG, "Reçu du Bridge: $msg")
                                listener?.onMessageReceived(msg)
                            } else if (bytesRead == -1) {
                                Log.w(TAG, "Fin de flux (déconnexion)")
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (isConnected) Log.e(TAG, "Erreur lecture: ${e.message}")
                    } finally {
                        if (isConnected) {
                            isConnected = false
                            listener?.onConnectionLost()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Échec connexion: ${e.message}")
                isConnected = false
                listener?.onConnectionLost()
            }
        }
    }

    /**
     * Envoie une commande au pont Erebus Bridge
     * @param command La commande à envoyer
     */
    fun send(command: String) {
        if (!isConnected || outputStream == null) {
            Log.w(TAG, "Impossible d'envoyer: non connecté. Cmd: $command")
            return
        }
        try {
            val data = (command + "\n").toByteArray(Charsets.UTF_8)
            outputStream!!.write(data)
            outputStream!!.flush()
            Log.d(TAG, "Envoyé au Bridge: $command")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur envoi: ${e.message}")
            isConnected = false
            listener?.onConnectionLost()
        }
    }

    /**
     * Déconnecte le client du pont
     */
    fun disconnect() {
        isConnected = false
        try {
            readThread?.interrupt()
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) { }
        socket = null
        Log.i(TAG, "Déconnecté du Bridge")
    }
    
    /**
     * Vérifie si le client est connecté
     * @return true si connecté, false sinon
     */
    fun isConnecting(): Boolean = isConnected
}
