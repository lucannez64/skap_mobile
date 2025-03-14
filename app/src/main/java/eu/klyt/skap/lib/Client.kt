package eu.klyt.skap.lib
import android.util.Log
import java.security.SecureRandom
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.ArrayList
import kotlin.text.split

const val API_URL = "https://skap.klyt.eu/"

// Extension pour convertir ByteArray en représentation Uint8Array
fun ByteArray.toUint8Array(): List<Int> {
    return this.map { byte -> byte.toInt() and 0xFF }
}

fun randomBytes(bytesLength: Int): ByteArray {
    val random = SecureRandom()
    val bytes = ByteArray(bytesLength)
    random.nextBytes(bytes)
    return bytes
}

fun encrypt(pass: Password, client: Client): Result<EP> {
    val encoder = BincodeEncoder();
    val passb = encoder.encodePassword(pass)
    val key = blake3(client.kyQ)
    val cipher = XChaCha20Poly1305Cipher()
    val c = cipher.encrypt(passb,key)
    val ciphertext = c.ciphertext
    val nonce = c.nonce
    val ep = EP(nonce, null, ciphertext)
    return Result.success(ep)
}

fun send(ep: EP, client: Client): EP {
    val key = blake3(client.kyQ)
    val cipher = XChaCha20Poly1305Cipher()
    val c = cipher.encrypt(ep.ciphertext,key)
    val ciphertext = c.ciphertext
    val nonce2 = c.nonce
    val ep2 = EP(ep.nonce1, nonce2, ciphertext)
    return ep2
}

fun decrypt(ep: EP, secretKey: ByteArray, kyQ: ByteArray): Result<Password> {
    val keySecret = blake3(secretKey)
    val keyKyQ = blake3(kyQ)
    val cipher = XChaCha20Poly1305Cipher()
    if (ep.nonce2 == null) {
        return Result.failure(Exception("Missing nonce2"))
    }
    val ciphertext2 = cipher.decrypt(ep.ciphertext,ep.nonce2,keySecret)
    val nonce = ep.nonce1
    val plaintext = cipher.decrypt(ciphertext2,nonce,keyKyQ)
    val pass = Decoded.decodePassword(plaintext)
    return if (pass == null) {
        Result.failure(Exception("Failed to Decode Password"))
    } else {
        Result.success(pass)
    }
}

enum class ShareStatus {
    Pending,
    Accepted,
    Rejected
}

data class SharedPass(
    val kem_ct: ByteArray,
    val ep: EP,
    val status: ShareStatus
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SharedPass

        if (!kem_ct.contentEquals(other.kem_ct)) return false
        if (ep != other.ep) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = kem_ct.contentHashCode()
        result = 31 * result + ep.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}

data class SharedByUser(
    val passId: String,
    val recipientIds: List<String>
)

data class SharedByUserEmail(
    val passId: String,
    val emails: List<String>,
    val statuses: List<ShareStatus>? = null
)

data class ReceivedCK(
    val email: String,
    val id: String,
    val kyP: ByteArray,
    val kyQ: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReceivedCK

        if (email != other.email) return false
        if (id != other.id) return false
        if (!kyP.contentEquals(other.kyP)) return false
        if (!kyQ.contentEquals(other.kyQ)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = email.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + kyP.contentHashCode()
        result = 31 * result + kyQ.contentHashCode()
        return result
    }
}

data class CreateAccountResult(
    val clientEx: ClientEx,
    val encodedFile: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CreateAccountResult

        if (clientEx != other.clientEx) return false
        if (!encodedFile.contentEquals(other.encodedFile)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = clientEx.hashCode()
        result = 31 * result + encodedFile.contentHashCode()
        return result
    }
}

suspend fun createAccount(email: String): Result<CreateAccountResult> {
    return withContext(Dispatchers.IO) {
        try {
            val mlKem = MlKem1024()
            val (kyP, kyQ) = mlKem.keygen()
            val mlDsa = MlDsa87()
            val (diP, diQ) = mlDsa.keygen()
            val secret = randomBytes(32)
            val client = Client(
                kyP = kyP,
                kyQ = kyQ,
                diP = diP,
                diQ = diQ,
                secret = secret
            )

            // Créer l'identité du client
            val clientId = CK(
                email = email,
                id = null,
                kyP = kyP,
                diP = diP
            )

            // Créer le ClientEx
            var clientEx = ClientEx(
                c = client,
                id = clientId
            )

            // Convertir les tableaux d'octets en objets JSON avec des indices numériques
            // Conversion en Uint8Array (valeurs non signées de 0 à 255)
            Log.i(null, "kyP size ${kyP.size} et diP size ${diP.size}")
            val kyPUint8 = kyP.toUint8Array()
            val diPUint8 = diP.toUint8Array()

            val clientIdJson = mapOf(
                "email" to email,
                "id" to null,
                "ky_p" to mapOf("bytes" to kyPUint8),
                "di_p" to mapOf("bytes" to diPUint8)
            )

            val jsonBody = Gson().toJson(clientIdJson)
            Log.i(null,"Envoi de la requête: $jsonBody")

            val request = Request.Builder()
                .url(API_URL + "create_user_json/")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = OkHttpClient().newCall(request).execute()

            Log.i(null,"Code de réponse: ${response.code}")
            val responseBody = response.body?.string()
            Log.i(null,"Corps de la réponse: $responseBody")

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }

            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }

            try {
                val responseJson = Gson().fromJson(responseBody, Map::class.java)
                Log.i(null,"$responseJson")
                val id = responseJson["id"] as String
                val email = responseJson["email"] as String
                Log.i(null, "ky_p ${responseJson["ky_p"]}")

                val kyPBytesMap = (responseJson["ky_p"] as Map<*,*>)["bytes"] as ArrayList<*>
                val diPBytesMap = (responseJson["di_p"] as Map<*, *>)["bytes"] as ArrayList<*>

                val kyP = ByteArray(kyPBytesMap.size) { i ->
                    when (val value = kyPBytesMap[i]) {
                        is Double -> value.toInt().toByte()
                        is Int -> value.toByte()
                        else -> {throw Exception("Dinguerie")}
                    }

                }

                val diP = ByteArray(diPBytesMap.size) { i ->
                    when (val value = diPBytesMap[i]) {
                        is Double -> value.toInt().toByte()
                        is Int -> value.toByte()
                        else -> {throw Exception("Dinguerie")}
                    }
                }

                val ck = CK(
                    email = email,
                    id = createUuid(id),
                    kyP = kyP,
                    diP = diP  // Notez que nous utilisons kyQ comme diP ici, selon la structure de la réponse
                )

                clientEx.id = ck
                val encoder = BincodeEncoder()
                val encodedClientEx = encoder.encodeClientEx(clientEx)
                val result = CreateAccountResult(clientEx, encodedClientEx)
                Result.success(result)
            } catch (e: Exception) {
                Log.i(null,"Erreur de parsing JSON: ${e.message}")
                Log.i(null,"JSON reçu: $responseBody")
                Result.failure(Exception("Erreur de parsing de la réponse: ${e.message}", e))
            }
        } catch (e: Exception) {
            Log.i(null,"Exception: ${e.message}")
            e.printStackTrace()
            Result.failure(Exception("Erreur lors de la création du compte: ${e.message}", e))
        }
    }
}

suspend fun auth(uuid: Uuid, client: Client): Result<String> {
    return withContext(Dispatchers.IO) {
        try {
            val requestChallenge = Request.Builder()
                .url(API_URL + "challenge_json/" + uuid.toString())
                .build()
            val responseChallenge = OkHttpClient().newCall(requestChallenge).execute()
            if (!responseChallenge.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${responseChallenge.code} - ${responseChallenge.message}"))
            }
            val responseBody = responseChallenge.body?.string()
            Log.i(null, "D $responseBody")

            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }
            try {
                val challengeJsonD = Gson().fromJson(responseBody, ByteArray::class.java)
                for (v in challengeJsonD) {
                    Log.i(null, v.toString())
                }
                val signature = MlDsa87().sign(client.diQ, client.diP,challengeJsonD)
                if (!signature.isSuccess) {
                    return@withContext Result.failure(Exception("Erreur server: can't sign the challenge"))
                }
                val signaturee = signature.getOrNull()
                val signatureu = signaturee?.toUint8Array()
                val verified = signaturee?.let { MlDsa87().verify(client.diP, challengeJsonD,it) }
                Log.i(null, verified.toString())
                val jsonBody = Gson().toJson(signatureu)
                Log.i(null,jsonBody)
                val requestVerify = Request.Builder()
                    .url(API_URL + "verify_json/" + uuid.toString())
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val responseVerify = OkHttpClient().newCall(requestVerify).execute()
                Log.i(null, "${requestVerify.url}")
                Log.i(null, responseVerify.message)
                Log.i(null, "${responseVerify.code}")
                if (!responseVerify.isSuccessful) {
                    return@withContext Result.failure(Exception("Erreur serveur: ${responseVerify.code} - ${responseVerify.message}"))
                }
                val r = responseVerify.headers["set-cookie"].toString().replaceFirst("token=","").split(
                    ";"[0]
                )[0]
                Log.i(null, "SS $r")
                val requestSync = Request.Builder()
                    .url(API_URL + "sync_json/" + uuid.toString())
                    .addHeader("Authorization", r)
                    .build()
                val responseSync = OkHttpClient().newCall(requestSync).execute()
                if (!responseSync.isSuccessful) {
                    return@withContext Result.failure(Exception("Erreur serveur: ${responseSync.code} - ${responseSync.message}"))
                }
                val responseSyncBody = responseSync.body?.string()
                val ciphertext = Gson().fromJson(responseSyncBody, ByteArray::class.java)
                val secret = MlKem1024().decapsulate(client.kyQ, ciphertext)
                if (secret.isFailure) {
                    return@withContext Result.failure(Exception("Erreur serveur: could'nt decapsulate the secret"))
                }
                client.secret = secret.getOrNull()
                return@withContext Result.success(r)
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Erreur de parsing de la réponse: ${e.message}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la création du compte: ${e.message}", e))
        }
    }
}

suspend fun getAll(token: String, uuid: Uuid, client: Client): Result<Passwords> {
    return withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_URL + "send_all_json/" + uuid.toString())
            .addHeader("Authorization", token)
            .build()

        val response = OkHttpClient().newCall(request).execute()
        if (!response.isSuccessful) {
            return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
        }
        val responseBody = response.body?.string()
        if (responseBody.isNullOrEmpty()) {
            return@withContext Result.failure(Exception("Réponse vide du serveur"))
        }
        val responseJson = Gson().fromJson(responseBody, Map::class.java)
        val passwords = responseJson["passwords"] as ArrayList<ArrayList<*>>
        val p = ArrayList<Pair<Password, Uuid>>()
        for (d in passwords) {
            val ee = d[0] as LinkedTreeMap<String, ArrayList<Int>>
            val ep = ee["nonce2"]?.toByteArray()
                ?.let { ee["ciphertext"]?.toByteArray()
                    ?.let { it1 -> ee["nonce"]?.toByteArray()?.let { it2 -> EP(it2, it, it1) } } }
            val id = d[1] as String
            val r = client.secret?.let {
                if (ep != null) {
                    decrypt(ep, it, client.kyQ)
                } else {
                    null
                }
            }
            if (r != null) {
                if (r.isFailure) {
                    Log.i(null, "Decrypt failed")
                    continue
                }
            }
            val z = r?.getOrNull()
            val uuid2 = createUuid(id)
            p.add(Pair(z, uuid2) as Pair<Password, Uuid>)
        }
        val password: Passwords = p.toTypedArray()
        Result.success(password)
    }    
}

fun ArrayList<Int>.toByteArray():ByteArray {
    return ByteArray(this.size) { i ->
        this[i].toByte()
    }
}