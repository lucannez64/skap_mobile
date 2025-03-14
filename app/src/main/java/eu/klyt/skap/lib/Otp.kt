package eu.klyt.skap.lib

import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and
import kotlin.math.pow

/**
 * Classe représentant un OTP (One-Time Password)
 */
data class Otp(
    val secret: String,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val issuer: String?
)

/**
 * Fonctions utilitaires pour les OTP
 */
object OtpUtils {
    
    /**
     * Crée un objet Otp à partir d'une URI TOTP
     * 
     * @param uri L'URI au format otpauth://totp/...
     * @return L'objet Otp ou null si l'URI est invalide
     */
    fun fromUri(uri: String): Otp? {
        try {
            val url = URL(uri)
            if (url.protocol != "otpauth") return null
            if (url.host != "totp") return null
            
            val queryParams = url.query.split("&").associate { 
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            }
            
            val secret = queryParams["secret"] ?: return null
            val algorithm = queryParams["algorithm"] ?: "SHA1"
            val digits = queryParams["digits"]?.toIntOrNull() ?: 6
            val period = queryParams["period"]?.toIntOrNull() ?: 30
            val issuer = queryParams["issuer"]
            
            return Otp(secret, algorithm, digits, period, issuer)
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Génère un code OTP basé sur l'objet Otp fourni
     * 
     * @param otp L'objet Otp contenant les paramètres
     * @return Le code OTP généré
     */
    fun generate(otp: Otp): String {
        // Obtenir le temps actuel en secondes
        val time = System.currentTimeMillis() / 1000
        
        // Calculer le compteur basé sur la période
        val counter = (time / otp.period).toLong()
        
        // Convertir le compteur en bytes (8 bytes, big-endian)
        val counterBytes = ByteArray(8)
        for (i in 0..7) {
            counterBytes[7 - i] = ((counter shr (i * 8)) and 0xFF).toByte()
        }
        
        // Décoder la clé secrète de base32
        val secretBytes = base32Decode(otp.secret)
        
        // Calculer le HMAC
        val hmac = when (otp.algorithm.uppercase()) {
            "SHA1" -> calculateHmac(secretBytes, counterBytes, "HmacSHA1")
            "SHA256" -> calculateHmac(secretBytes, counterBytes, "HmacSHA256")
            "SHA512" -> calculateHmac(secretBytes, counterBytes, "HmacSHA512")
            else -> calculateHmac(secretBytes, counterBytes, "HmacSHA1") // Par défaut
        }
        
        // Extraire un nombre à partir du HMAC (truncation dynamique)
        val offset = (hmac[hmac.size - 1] and 0x0F).toInt()
        val codeBytes = hmac.sliceArray(offset until offset + 4)
        val code = (((codeBytes[0].toInt() and 0x7F) shl 24) or
                ((codeBytes[1].toInt() and 0xFF) shl 16) or
                ((codeBytes[2].toInt() and 0xFF) shl 8) or
                (codeBytes[3].toInt() and 0xFF)) % 10.0.pow(otp.digits).toInt()

        return code.toString().padStart(otp.digits, '0')
    }
    
    /**
     * Calcule un HMAC avec l'algorithme spécifié
     */
    private fun calculateHmac(key: ByteArray, data: ByteArray, algorithm: String): ByteArray {
        val mac = Mac.getInstance(algorithm)
        val keySpec = SecretKeySpec(key, algorithm)
        mac.init(keySpec)
        return mac.doFinal(data)
    }
    
    /**
     * Décode une chaîne Base32 en bytes
     */
    private fun base32Decode(input: String): ByteArray {
        val cleanInput = input.uppercase().replace(Regex("[^A-Z2-7]"), "")
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        
        for (c in cleanInput) {
            val value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c)
            if (value < 0) continue
            
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        
        return output.toByteArray()
    }
} 