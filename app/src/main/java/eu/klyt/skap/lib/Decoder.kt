package eu.klyt.skap.lib

import java.nio.ByteBuffer
import kotlin.uuid.Uuid as TTID
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.experimental.and
import java.io.File
import java.nio.file.Paths
import java.util.Arrays

const val KY_PUBLIC_KEY_SIZE = 1568
const val KY_SECRET_KEY_SIZE = 3168
const val DI_PUBLIC_KEY_SIZE = 2592
const val DI_SECRET_KEY_SIZE = 4896
const val KYBER_SSBYTES = 32

// Types de base
typealias KyPublicKey = ByteArray
typealias KySecretKey = ByteArray
typealias DiPublicKey = ByteArray
typealias DiSecretKey = ByteArray
typealias Passwords = Array<Pair<Password, Uuid>>

// Classes de données
data class Uuid(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Uuid
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }


    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override fun toString(): String {
        return TTID.fromByteArray(bytes).toString()
    }
}

fun createUuid(input: String): Uuid {
    // Convert UUID string to byte array
    val hex = input.replace("-", "")
    val bytes = ByteArray(16)
    for (i in 0 until 16) {
        bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return Uuid(bytes)
}

data class CK(
    val email: String,
    val id: Uuid?,
    val kyP: KyPublicKey,
    val diP: DiPublicKey
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CK

        if (email != other.email) return false
        if (id != other.id) return false
        if (!kyP.contentEquals(other.kyP)) return false
        if (!diP.contentEquals(other.diP)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = email.hashCode()
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + kyP.contentHashCode()
        result = 31 * result + diP.contentHashCode()
        return result
    }

    fun toJson(): Map<String, Any?> {
        val kyPList = kyP.map { it.toInt() and 0xFF }
        val diPList = diP.map { it.toInt() and 0xFF }
        if (id == null) {
            return mapOf(
                "email" to email,
                "id" to null,
                "ky_p" to kyPList,
                "di_p" to diPList
            )
        }
        return mapOf(
            "email" to email,
            "id" to id.toString(),
            "ky_p" to kyPList,
            "di_p" to diPList
        )
    }
}

data class Client(
    val kyP: KyPublicKey,
    val kyQ: KySecretKey,
    val diP: DiPublicKey,
    val diQ: DiSecretKey,
    var secret: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Client
        return kyP.contentEquals(other.kyP) &&
                kyQ.contentEquals(other.kyQ) &&
                diP.contentEquals(other.diP) &&
                diQ.contentEquals(other.diQ) &&
                (secret == null && other.secret == null || 
                 secret != null && other.secret != null && secret.contentEquals(other.secret))
    }

    override fun hashCode(): Int {
        var result = kyP.contentHashCode()
        result = 31 * result + kyQ.contentHashCode()
        result = 31 * result + diP.contentHashCode()
        result = 31 * result + diQ.contentHashCode()
        result = 31 * result + (secret?.contentHashCode() ?: 0)
        return result
    }
}

data class ClientEx(
    val c: Client,
    var id: CK
)
data class Password(
    val password: String,
    val app_id: String?,
    val username: String,
    val description: String?,
    val url: String?,
    val otp: String?
)

data class EP(
    val nonce1: ByteArray,
    val nonce2: ByteArray?,
    val ciphertext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EP
        return nonce1.contentEquals(other.nonce1) &&
                (nonce2 == null && other.nonce2 == null || 
                 nonce2 != null && other.nonce2 != null && nonce2.contentEquals(other.nonce2)) &&
                ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = nonce1.contentHashCode()
        result = 31 * result + (nonce2?.contentHashCode() ?: 0)
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}

// Classe pour l'encodage binaire
class BincodeEncoder(initialSize: Int = 16384) {
    private var buffer: ByteArray = ByteArray(initialSize)
    private var offset: Int = 0

    private fun ensureCapacity(additionalBytes: Int) {
        if (offset + additionalBytes > buffer.size) {
            val newSize = maxOf(buffer.size * 2, offset + additionalBytes)
            val newBuffer = ByteArray(newSize)
            System.arraycopy(buffer, 0, newBuffer, 0, offset)
            buffer = newBuffer
        }
    }

    private fun writeUint64(value: Long) {
        ensureCapacity(8)
        val byteBuffer = ByteBuffer.wrap(buffer, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.putLong(value)
        offset += 8
    }

    private fun encodeU8(value: Int) {
        ensureCapacity(1)
        buffer[offset++] = value.toByte()
    }

    private fun encodeFixedBytesWithLength(bytes: ByteArray) {
        writeUint64(bytes.size.toLong())
        ensureCapacity(bytes.size)
        System.arraycopy(bytes, 0, buffer, offset, bytes.size)
        offset += bytes.size
    }

    private fun encodeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        encodeFixedBytesWithLength(bytes)
    }

    private fun <T> encodeOption(value: T?, encoder: (T) -> Unit) {
        if (value != null) {
            encodeU8(1)
            encoder(value)
        } else {
            encodeU8(0)
        }
    }

    private fun encodeUuid(uuid: Uuid) {
        writeUint64(16.toLong())
        ensureCapacity(16)
        System.arraycopy(uuid.bytes, 0, buffer, offset, 16)
        offset += 16
    }

    private fun encodeKyPublicKey(key: KyPublicKey) {
        encodeFixedBytesWithLength(key)
    }

    private fun encodeKySecretKey(key: KySecretKey) {
        encodeFixedBytesWithLength(key)
    }

    private fun encodeDiPublicKey(key: DiPublicKey) {
        encodeFixedBytesWithLength(key)
    }

    private fun encodeDiSecretKey(key: DiSecretKey) {
        encodeFixedBytesWithLength(key)
    }

    private fun encodeCK(ck: CK) {
        encodeString(ck.email)
        encodeOption(ck.id) { id ->
            encodeUuid(id)
        }
        encodeKyPublicKey(ck.kyP)
        encodeDiPublicKey(ck.diP)
    }

    private fun encodeClient(client: Client) {
        encodeKyPublicKey(client.kyP)
        encodeKySecretKey(client.kyQ)
        encodeDiPublicKey(client.diP)
        encodeDiSecretKey(client.diQ)
        encodeOption(client.secret) { secret ->
            ensureCapacity(secret.size)
            System.arraycopy(secret, 0, buffer, offset, secret.size)
            offset += secret.size
        }
    }

    fun encodeClientEx(clientEx: ClientEx): ByteArray {
        offset = 0
        encodeClient(clientEx.c)
        encodeCK(clientEx.id)
        val result = ByteArray(offset)
        System.arraycopy(buffer, 0, result, 0, offset)
        return result
    }

    fun encodeEP(ep: EP): ByteArray {
        offset = 0
        encodeFixedBytesWithLength(ep.ciphertext)
        encodeFixedBytesWithLength(ep.nonce1)
        encodeOption(ep.nonce2) { nonce2 ->
            encodeFixedBytesWithLength(nonce2)
        }
        val result = ByteArray(offset)
        System.arraycopy(buffer, 0, result, 0, offset)
        return result
    }

    fun encodePassword(password: Password): ByteArray {
        offset = 0
        encodeString(password.password)
        encodeOption(password.app_id) { app_id ->
            encodeString(app_id)
        }
        encodeString(password.username)
        encodeOption(password.description) { description ->
            encodeString(description)
        }
        encodeOption(password.url) { url ->
            encodeString(url)
        }
        encodeOption(password.otp) { otp ->
            encodeString(otp)
        }
        val result = ByteArray(offset)
        System.arraycopy(buffer, 0, result, 0, offset)
        return result
    }

    companion object {
        fun createKyPublicKey(data: ByteArray): KyPublicKey {
            if (data.size != KY_PUBLIC_KEY_SIZE) {
                throw IllegalArgumentException("Invalid KyPublicKey size: ${data.size}, expected $KY_PUBLIC_KEY_SIZE")
            }
            return data
        }

        fun createKySecretKey(data: ByteArray): KySecretKey {
            if (data.size != KY_SECRET_KEY_SIZE) {
                throw IllegalArgumentException("Invalid KySecretKey size: ${data.size}, expected $KY_SECRET_KEY_SIZE")
            }
            return data
        }

        fun createDiPublicKey(data: ByteArray): DiPublicKey {
            if (data.size != DI_PUBLIC_KEY_SIZE) {
                throw IllegalArgumentException("Invalid DiPublicKey size: ${data.size}, expected $DI_PUBLIC_KEY_SIZE")
            }
            return data
        }

        fun createDiSecretKey(data: ByteArray): DiSecretKey {
            if (data.size != DI_SECRET_KEY_SIZE) {
                throw IllegalArgumentException("Invalid DiSecretKey size: ${data.size}, expected $DI_SECRET_KEY_SIZE")
            }
            return data
        }

        fun createUuid(input: ByteArray): Uuid {
            if (input.size != 16) {
                throw IllegalArgumentException("Invalid UUID size: ${input.size}, expected 16")
            }
            return Uuid(input)
        }

        fun createUuid(input: String): Uuid {
            // Convertir une chaîne UUID en bytes
            val uuidBytes = ByteArray(16)
            val parts = input.replace("-", "")
            if (parts.length != 32) {
                throw IllegalArgumentException("Invalid UUID string format")
            }
            for (i in 0 until 16) {
                uuidBytes[i] = parts.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return Uuid(uuidBytes)
        }

    }
}

// Fonctions de décodage
object Decoded {
    fun decodePassword(bytes: ByteArray): Password? {
        if (bytes.isEmpty()) return null
        
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Lire le mot de passe
        val passwordLength = buffer.getLong().toInt()
        val passwordBytes = ByteArray(passwordLength)
        buffer.get(passwordBytes)
        val password = String(passwordBytes, StandardCharsets.UTF_8)

        val appIdPresent = buffer.get().toInt()
        var app_id: String? = null
        if (appIdPresent == 1) {
            val appIdLength = buffer.getLong().toInt()
            val appIdBytes = ByteArray(appIdLength)
            buffer.get(appIdBytes)
            app_id = String(appIdBytes, StandardCharsets.UTF_8)
        } else if (appIdPresent != 0) {
            return null // Format incorrect
        }



        // Lire le nom
        val usernameLength = buffer.getLong().toInt()
        val usernameBytes = ByteArray(usernameLength)
        buffer.get(usernameBytes)
        val username = String(usernameBytes, StandardCharsets.UTF_8)
        val descriptionPresent = buffer.get().toInt()
        var description: String? = null
        if (descriptionPresent == 1) {
            val descriptionLength = buffer.getLong().toInt()
            val descriptionBytes = ByteArray(descriptionLength)
            buffer.get(descriptionBytes)
            description = String(descriptionBytes, StandardCharsets.UTF_8)
        } else if (descriptionPresent != 0) {
            return null // Format incorrect
        }


        // Lire l'URL (optionnel)
        val urlPresent = buffer.get().toInt()
        var url: String? = null
        if (urlPresent == 1) {
            val urlLength = buffer.getLong().toInt()
            val urlBytes = ByteArray(urlLength)
            buffer.get(urlBytes)
            url = String(urlBytes, StandardCharsets.UTF_8)
        } else if (urlPresent != 0) {
            return null // Format incorrect
        }
                
        // Lire l'OTP (optionnel)
        val otpPresent = buffer.get().toInt()
        var otp: String? = null
        if (otpPresent == 1) {
            val otpLength = buffer.getLong().toInt()
            if (otpLength < 0 || otpLength > bytes.size - buffer.position()) return null
            val otpBytes = ByteArray(otpLength)
            buffer.get(otpBytes)
            otp = String(otpBytes, StandardCharsets.UTF_8)
        } else if (otpPresent != 0) {
            return null // Format incorrect
        }
        
        return Password(password, app_id, username, description, url, otp)
    }

    fun decodeEP(bytes: ByteArray): EP? {
        if (bytes.isEmpty()) return null
        
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
        // Lire kemCt
        val ciphertextLength = buffer.getLong().toInt()
        val ciphertext = ByteArray(ciphertextLength)
        buffer.get(ciphertext)
        
        // Lire nonce1
        val nonce1Length = buffer.getLong().toInt()
        if (nonce1Length < 0 || nonce1Length > bytes.size - buffer.position()) return null
        val nonce1 = ByteArray(nonce1Length)
        buffer.get(nonce1)
        
        // Lire nonce2 (optionnel)
        val nonce2Present = buffer.get().toInt()
        var nonce2: ByteArray? = null
        if (nonce2Present == 1) {
            val nonce2Length = buffer.getLong().toInt()
            if (nonce2Length < 0 || nonce2Length > bytes.size - buffer.position()) return null
            nonce2 = ByteArray(nonce2Length)
            buffer.get(nonce2)
        } else if (nonce2Present != 0) {
            return null // Format incorrect
        }        
        return EP(nonce1, nonce2, ciphertext)
    }

    fun decodeClient(bytes: ByteArray): Pair<Client, ByteBuffer>? {
        if (bytes.isEmpty()) return null        
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
        // Lire kyP
        val kyPLength = buffer.getLong().toInt()
        val kyP = ByteArray(KY_PUBLIC_KEY_SIZE)
        buffer.get(kyP)
        
        // Lire kyQ
        val kyQLength = buffer.getLong().toInt()
        val kyQ = ByteArray(KY_SECRET_KEY_SIZE)
        buffer.get(kyQ)
        // Lire diP
        val diPLength = buffer.getLong().toInt()
        val diP = ByteArray(DI_PUBLIC_KEY_SIZE)
        buffer.get(diP)
        // Lire diQ
        val diQLength = buffer.getLong().toInt()
        val diQ = ByteArray(DI_SECRET_KEY_SIZE)
        buffer.get(diQ)
        // Lire secret (optionnel)
        val secretPresent = ByteArray(1)
        buffer.get(secretPresent)
        var secret: ByteArray? = null
        if (secretPresent[0].toInt() == 1) {
            secret = ByteArray(KYBER_SSBYTES)
            buffer.get(secret)
        } else if (secretPresent[0].toInt() != 0) {
            return null// Format incorrect
        }
        
        return Pair(Client(kyP, kyQ, diP, diQ, secret), buffer)
    }

    fun decodeClientEx(bytes: ByteArray): ClientEx? {
        val (client, buffer) = decodeClient(bytes) ?: return null
        // Calculer la position après le client        
        // Décoder CK à partir de la position calculée
        val ck = decodeCK(buffer) ?: return null
        
        return ClientEx(client, ck)
    }

    private fun decodeCK(buffer: ByteBuffer): CK? {
        // Lire email
        val emailLength = buffer.getLong().toInt()
        val emailBytes = ByteArray(emailLength)
        buffer.get(emailBytes)
        val email = String(emailBytes, StandardCharsets.UTF_8)
        // Lire id (optionnel)
        val idPresent = ByteArray(1)
        buffer.get(idPresent)
        var id: Uuid? = null
        if (idPresent[0].toInt() == 1) {
            val idLength = buffer.getLong().toInt()
            val idBytes = ByteArray(16)
            buffer.get(idBytes)

            id = Uuid(idBytes)
        } else if (idPresent[0].toInt() != 0) {
            return null // Format incorrect
        }
        
        // Lire kyP
        val kyPLength = buffer.getLong().toInt()
        val kyP = ByteArray(KY_PUBLIC_KEY_SIZE)
        buffer.get(kyP)
        // Lire diP
        val diPLength = buffer.getLong().toInt()
        val diP = ByteArray(DI_PUBLIC_KEY_SIZE)
        buffer.get(diP)
        return CK(email, id, kyP, diP)
    }

    fun uuidToString(uuid: Uuid): String {
        val bytes = uuid.bytes
        val sb = StringBuilder()
        for (i in 0 until 16) {
            sb.append(String.format("%02x", bytes[i] and 0xFF.toByte()))
            if (i == 3 || i == 5 || i == 7 || i == 9) {
                sb.append('-')
            }
        }
        return sb.toString()
    }

    fun toHexString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b and 0xFF.toByte()))
        }
        return sb.toString()
    }
} 

fun ByteArray.print() {
    val sb = StringBuilder()
    for (b in this) {
        sb.append(String.format("%d", b and 0xFF.toByte()))
    }
    println(sb.toString())
}

fun ByteArray.tostring(): String {
    val sb = StringBuilder()
    for (b in this) {
        sb.append(String.format("%d", b))
    }
    return sb.toString()
}

fun ByteArray.toULong(littleEndian: Boolean = false): ULong {
    require(this.size <= 8) { "ByteArray too large to convert to ULong" }
    
    val bytes = if (this.size < 8) {
        // Pad with zeros if fewer than 8 bytes
        ByteArray(8).also { padded ->
            this.copyInto(
                destination = padded,
                destinationOffset = if (littleEndian) 0 else 8 - this.size
            )
        }
    } else {
        this
    }
    
    return if (littleEndian) {
        bytes.foldIndexed(0UL) { index, acc, byte ->
            acc or (byte.toUByte().toULong() shl (index * 8))
        }
    } else {
        // Big-endian
        bytes.foldIndexed(0UL) { index, acc, byte ->
            acc or (byte.toUByte().toULong() shl ((7 - index) * 8))
        }
    }
}
