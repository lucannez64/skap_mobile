package eu.klyt.skap.lib
import android.util.Log
import androidx.compose.runtime.key
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
import java.util.stream.Collectors
import kotlin.text.split
import kotlin.getOrThrow

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
    val ep = EP(c.nonce, null, c.ciphertext)
    return Result.success(ep)
}

fun send(ep: EP, client: Client): EP {
    val key = blake3(client.secret!!)
    val cipher = XChaCha20Poly1305Cipher()
    val c = cipher.encrypt(ep.ciphertext,key)
    val ep2 = EP(ep.nonce1, c.nonce, c.ciphertext)
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

fun SharedStatusfromInt(a: Int): ShareStatus? {
    return when(a) {
        0 -> ShareStatus.Pending
        1 -> ShareStatus.Accepted
        2 -> ShareStatus.Rejected
        else -> null
    }
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

    fun statusInt(): Int {
        return when (this.status) {
            ShareStatus.Pending -> 0
            ShareStatus.Accepted -> 1
            ShareStatus.Rejected -> 2
        }
    }
}

data class SharedByUser(
    val passId: String,
    val recipientIds: Array<String>
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

data class AllPasswords(
    val passwords: Passwords,
    val sharedPasswords: SharedPasswords
)

fun decryptShared(sharedPass: SharedPass, client: Client): Result<Password> {
    val sharedSecret = MlKem1024().decapsulate(client.kyQ, sharedPass.kem_ct)
    if (sharedSecret.isFailure) {
        return Result.failure(Exception("Erreur serveur: could'nt decapsulate the secret"))
    }
    val secretKey = blake3(sharedSecret.getOrNull()!!)
    val cipher = XChaCha20Poly1305Cipher()
    val nonce = sharedPass.ep.nonce1
    val ciphertext = sharedPass.ep.ciphertext
    val plaintext = cipher.decrypt(ciphertext,nonce,secretKey)
    val pass = Decoded.decodePassword(plaintext)
    return if (pass == null) {
        Result.failure(Exception("Failed to Decode Password"))
    } else {
        Result.success(pass)
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

            val request = Request.Builder()
                .url(API_URL + "create_user_json/")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = OkHttpClient().newCall(request).execute()

            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }

            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }

            try {
                val responseJson = Gson().fromJson(responseBody, Map::class.java)
                val id = responseJson["id"] as String
                val email = responseJson["email"] as String

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
                Result.failure(Exception("Erreur de parsing de la réponse: ${e.message}", e))
            }
        } catch (e: Exception) {
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

            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }
            try {
                val challengeJsonD = Gson().fromJson(responseBody, ByteArray::class.java)
                val signature = MlDsa87().sign(client.diQ, client.diP,challengeJsonD)
                if (!signature.isSuccess) {
                    return@withContext Result.failure(Exception("Erreur server: can't sign the challenge"))
                }
                val signaturee = signature.getOrNull()
                val signatureu = signaturee?.toUint8Array()
                val verified = signaturee?.let { MlDsa87().verify(client.diP, challengeJsonD,it) }
                val jsonBody = Gson().toJson(signatureu)
                val requestVerify = Request.Builder()
                    .url(API_URL + "verify_json/" + uuid.toString())
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val responseVerify = OkHttpClient().newCall(requestVerify).execute()
                if (!responseVerify.isSuccessful) {
                    return@withContext Result.failure(Exception("Erreur serveur: ${responseVerify.code} - ${responseVerify.message}"))
                }
                val r = responseVerify.headers["set-cookie"].toString().replaceFirst("token=","").split(
                    ";"[0]
                )[0]
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

suspend fun getAll(token: String, uuid: Uuid, client: Client): Result<AllPasswords> {
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
        val p = passwords.parallelStream().map {
            d ->
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
                        null
                    }
                }
                val z = r?.getOrNull()
                val uuid2 = createUuid(id)
                Pair(z, uuid2) as Pair<Password, Uuid>
        }.filter { it -> it != null }.collect(Collectors.toList()).toTypedArray()
        val shared = responseJson["shared_passes"] as ArrayList<ArrayList<*>>
        val s = shared.parallelStream().map { d ->
            val id = d[1] as String
            val id2 = d[2] as String
            val uuid2 = createUuid(id)
            val uuid3 = createUuid(id2)
            val ee = d[0] as LinkedTreeMap<String, *>
            val kem_ct = ee["kem_ct"] as ArrayList<Int>
            val eep = ee["ep"] as LinkedTreeMap<String, *>
            val nonce = eep["nonce"] as ArrayList<Int>
            val nonce2 = null
            val ciphertext = eep["ciphertext"] as ArrayList<Int>
            val status = SharedStatusfromInt((ee["status"] as Double).toInt())
            val ep = EP(nonce.toByteArray(), nonce2, ciphertext.toByteArray())
            val sharedPass = SharedPass(kem_ct.toByteArray(), ep, status!!)
            val pass = decryptShared(sharedPass, client)
            if (pass.isFailure) {
                null
            } else {
                Quadruple(pass.getOrNull()!!, uuid2, uuid3, sharedPass.status)
            }
        }.filter { it != null }.collect(Collectors.toList()).toTypedArray() as SharedPasswords
        Result.success(AllPasswords(p, s))
    }    
}

suspend fun deletePass(token: String, uuid: Uuid, uuid2: Uuid): Exception? {
    return withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_URL+"delete_pass_json/"+uuid.toString()+"/"+uuid2.toString())
            .addHeader("Authorization", token)
            .build()
        val response = OkHttpClient().newCall(request).execute()
        if (response.isSuccessful) {
            null
        } else {
            Exception("Erreur lors de la suppression d'un mot de pass ${response.message}")
        }
    }
}

fun ArrayList<Int>.toByteArray():ByteArray {
    return ByteArray(this.size) { i ->
        this[i].toByte()
    }
}

/**
 * Crée un nouveau mot de passe
 * @param token Token d'authentification
 * @param uuid UUID de l'utilisateur
 * @param pass Mot de passe à créer
 * @param client Client actuel
 * @return Résultat de l'opération
 */
suspend fun createPass(token: String, uuid: Uuid, pass: Password, client: Client): Result<String> {
    return withContext(Dispatchers.IO) {
        try {
            val encrypted = encrypt(pass, client)
            if (encrypted.isFailure) {
                return@withContext Result.failure(encrypted.exceptionOrNull() ?: Exception("Échec du chiffrement"))
            }
            
            val ep = encrypted.getOrNull()!!
            val ep2 = send(ep, client)
            
            val epJson = mapOf(
                "ciphertext" to ep2.ciphertext.toUint8Array(),
                "nonce" to ep2.nonce1.toUint8Array(),
                "nonce2" to ep2.nonce2?.toUint8Array()
            )
            
            val jsonBody = Gson().toJson(epJson)
            
            val request = Request.Builder()
                .url(API_URL + "create_pass_json/" + uuid.toString())
                .addHeader("Authorization", token)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
                
            val response = OkHttpClient().newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }
            
            Result.success(responseBody)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la création du mot de passe: ${e.message}", e))
        }
    }
}

/**
 * Met à jour un mot de passe existant
 * @param token Token d'authentification
 * @param uuid UUID de l'utilisateur
 * @param passUuid UUID du mot de passe à mettre à jour
 * @param pass Nouveau mot de passe
 * @param client Client actuel
 * @return Résultat de l'opération
 */
suspend fun updatePass(token: String, uuid: Uuid, passUuid: Uuid, pass: Password, client: Client): Result<String> {
    return withContext(Dispatchers.IO) {
        try {
            val encrypted = encrypt(pass, client)
            if (encrypted.isFailure) {
                return@withContext Result.failure(encrypted.exceptionOrNull() ?: Exception("Échec du chiffrement"))
            }
            
            val ep = encrypted.getOrNull()!!
            val ep2 = send(ep, client)
            Log.i(null,ep2.toString())
            val secret = client.secret!!
            Log.i(null, secret.size.toString())
            val decrypted = decrypt(ep2, secret, client.kyQ)
            Log.i(null, decrypted.toString())
            val epJson = mapOf(
                "ciphertext" to ep2.ciphertext.toUint8Array(),
                "nonce" to ep2.nonce1.toUint8Array(),
                "nonce2" to ep2.nonce2?.toUint8Array()
            )
            Log.i(null, ep2.ciphertext.size.toString())
            Log.i(null, ep2.nonce2?.size.toString())

            val jsonBody = Gson().toJson(epJson)
            
            val request = Request.Builder()
                .url(API_URL + "update_pass_json/" + uuid.toString()+ "/" + passUuid.toString())
                .addHeader("Authorization", token)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
                
            val response = OkHttpClient().newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }
            
            Result.success(responseBody)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la mise à jour du mot de passe: ${e.message}", e))
        }
    }
}

/**
 * Chiffre un mot de passe pour le partage avec un autre utilisateur
 * @param password Mot de passe à partager
 * @param recipientPublicKey Clé publique du destinataire
 * @param client Client actuel
 * @return Mot de passe partagé chiffré ou erreur
 */
fun shareEncrypt(password: Password, recipientPublicKey: ByteArray, client: Client): Result<SharedPass> {
    return try {
        // Chiffrer le mot de passe avec la clé privée du client
        val encryptedPass = encrypt(password, client)
        if (encryptedPass.isFailure) {
            return Result.failure(encryptedPass.exceptionOrNull() ?: Exception("Échec du chiffrement du mot de passe"))
        }
        
        // Générer une clé partagée avec la clé publique du destinataire
        val mlKem = MlKem1024()
        val encapsulation = mlKem.encapsulate(recipientPublicKey)
        
        val (cipherText, sharedSecret) = encapsulation
        
        // Chiffrer le mot de passe avec la clé partagée
        val secretKey = blake3(sharedSecret)
        val cipher = XChaCha20Poly1305Cipher()
        
        // Encoder le mot de passe
        val encoder = BincodeEncoder()
        val epBytes = encoder.encodePassword(password)
        
        // Chiffrer le mot de passe
        val c = cipher.encrypt(epBytes, secretKey)
        
        // Créer l'EP chiffré
        val sharedEP = EP(c.nonce, null, c.ciphertext)
        
        // Créer le SharedPass
        val sharedPass = SharedPass(
            kem_ct = cipherText,
            ep = sharedEP,
            status = ShareStatus.Pending
        )
        
        Result.success(sharedPass)
    } catch (e: Exception) {
        Result.failure(Exception("Erreur lors du chiffrement pour le partage: ${e.message}", e))
    }
}

/**
 * Récupère l'UUID d'un utilisateur à partir de son email
 * @param email Email de l'utilisateur
 * @return UUID de l'utilisateur ou null en cas d'erreur
 */
suspend fun getUuidFromEmail(email: String): Result<String> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL + "get_uuid_from_email/" + email)
                .build()
                
            val response = OkHttpClient().newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }
            
            Result.success(responseBody)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la récupération de l'UUID: ${e.message}", e))
        }
    }
}

/**
 * Récupère la clé publique d'un utilisateur
 * @param uuid UUID de l'utilisateur
 * @return Clé publique de l'utilisateur ou null en cas d'erreur
 */
suspend fun getPublicKey(token: String, uuid: Uuid): Result<ByteArray> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL + "get_public_key/" + uuid.toString())
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
            
            val result = Gson().fromJson(responseBody, ByteArray::class.java)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la récupération de la clé publique: ${e.message}", e))
        }
    }
}

/**
 * Partage un mot de passe avec un autre utilisateur
 * @param token Token d'authentification
 * @param ownerUuid UUID du propriétaire du mot de passe
 * @param passUuid UUID du mot de passe à partager
 * @param recipientEmail Email du destinataire
 * @param client Client actuel
 * @param password Mot de passe à partager
 * @return Résultat de l'opération
 */
suspend fun sharePass(
    token: String,
    ownerUuid: Uuid,
    passUuid: Uuid,
    recipientEmail: String,
    client: Client,
    password: Password
): Result<String> {
    return withContext(Dispatchers.IO) {
        try {
            // Récupérer l'UUID du destinataire
            val recipientUuidResult = getUuidFromEmail(recipientEmail)
            if (recipientUuidResult.isFailure) {
                return@withContext Result.failure(Exception("Échec de la récupération de l'UUID du destinataire"))
            }
            
            val recipientUuidStr = recipientUuidResult.getOrNull()!!
            val recipientUuid = createUuid(recipientUuidStr)
            
            // Récupérer la clé publique du destinataire
            val recipientPublicKeyResult = getPublicKey(token, recipientUuid)
            if (recipientPublicKeyResult.isFailure) {
                return@withContext Result.failure(Exception("Échec de la récupération de la clé publique du destinataire"))
            }
            
            val recipientPublicKey = recipientPublicKeyResult.getOrNull()!!
            
            // Chiffrer le mot de passe pour le partage
            val sharedPassResult = shareEncrypt(password, recipientPublicKey, client)
            if (sharedPassResult.isFailure) {
                return@withContext Result.failure(sharedPassResult.exceptionOrNull() ?: Exception("Échec du chiffrement pour le partage"))
            }
            
            val sharedPass = sharedPassResult.getOrNull()!!
            // Convertir le SharedPass en format JSON pour l'API
            val sharedPassJson = mapOf(
                "kem_ct" to sharedPass.kem_ct.toUint8Array(),
                "ep" to mapOf(
                    "ciphertext" to sharedPass.ep.ciphertext.toUint8Array(),
                    "nonce" to sharedPass.ep.nonce1.toUint8Array(),
                    "nonce2" to sharedPass.ep.nonce2?.toUint8Array()
                ),
                "status" to sharedPass.statusInt()
            )
            
            val jsonBody = Gson().toJson(sharedPassJson)
            
            // Envoyer la requête au serveur
            val request = Request.Builder()
                .url(API_URL + "share_pass_json/" + 
                    ownerUuid.toString() + "/" + 
                    passUuid.toString() + "/" + 
                    recipientUuid.toString())
                .addHeader("Authorization", token)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
                
            val response = OkHttpClient().newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }
            
            Result.success(responseBody)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors du partage du mot de passe: ${e.message}", e))
        }
    }
}

/**
 * Annule le partage d'un mot de passe
 * @param token Token d'authentification
 * @param ownerUuid UUID du propriétaire du mot de passe
 * @param passUuid UUID du mot de passe
 * @param recipientUuid UUID du destinataire
 * @return Résultat de l'opération
 */
suspend fun unsharePass(
    token: String,
    ownerUuid: Uuid,
    passUuid: Uuid,
    recipientUuid: Uuid
): Result<String> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL + "unshare_pass_json/" + 
                    ownerUuid.toString() + "/" + 
                    passUuid.toString() + "/" + 
                    recipientUuid.toString())
                .addHeader("Authorization", token)
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
                
            val response = OkHttpClient().newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }
            
            Result.success(responseBody)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de l'annulation du partage: ${e.message}", e))
        }
    }
}

/**
 * Récupère un mot de passe partagé
 * @param token Token d'authentification
 * @param recipientUuid UUID du destinataire
 * @param ownerUuid UUID du propriétaire du mot de passe
 * @param passUuid UUID du mot de passe
 * @param client Client actuel
 * @return Le mot de passe déchiffré ou une erreur
 */
suspend fun getSharedPass(
    token: String,
    recipientUuid: Uuid,
    ownerUuid: Uuid,
    passUuid: Uuid,
    client: Client
): Result<Password> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL + "get_shared_pass_json/" + 
                    recipientUuid.toString() + "/" + 
                    ownerUuid.toString() + "/" + 
                    passUuid.toString())
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
            
            val sharedPassJson = Gson().fromJson(responseBody, Map::class.java)
            
            // Convertir le JSON en SharedPass
            val kemCt = (sharedPassJson["kem_ct"] as ArrayList<Int>).toByteArray()
            val epJson = sharedPassJson["ep"] as Map<*, *>
            val ciphertext = (epJson["ciphertext"] as ArrayList<Int>).toByteArray()
            val nonce = (epJson["nonce"] as ArrayList<Int>).toByteArray()
            val nonce2 = (epJson["nonce2"] as? ArrayList<Int>)?.toByteArray()
            val status = sharedPassJson["status"] as Int
            
            val ep = EP(nonce, nonce2, ciphertext)
            val sharedPass = SharedPass(
                kem_ct = kemCt,
                ep = ep,
                status = ShareStatus.values()[status]
            )
            
            // Déchiffrer le mot de passe partagé
            decryptShared(sharedPass, client)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la récupération du mot de passe partagé: ${e.message}", e))
        }
    }
}

/**
 * Récupère les mots de passe partagés par un utilisateur
 * @param token Token d'authentification
 * @param ownerUuid UUID du propriétaire
 * @return Liste des mots de passe partagés ou null en cas d'erreur
 */
suspend fun getSharedByUser(token: String, ownerUuid: Uuid): Result<List<SharedByUser>> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL + "get_shared_by_user/" + ownerUuid.toString())
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
            Log.i(null, responseBody)
            val r = Gson().fromJson(responseBody, Array::class.java)
            val b = r.map {
                i->
                val rr = i as Map<String,*>
                val passId = rr["pass_id"] as String
                val recipientIds = rr["recipient_ids"] as ArrayList<String>
                SharedByUser(passId, recipientIds.toTypedArray())
            }

            Result.success(b)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la récupération des mots de passe partagés: ${e.message}", e))
        }
    }
}

/**
 * Récupère les UUIDs à partir des emails
 * @param token Token d'authentification
 * @param emails Liste des emails
 * @return Liste des UUIDs correspondants ou null en cas d'erreur
 */
suspend fun getUuidsFromEmails(token: String, emails: List<String>): Result<List<Uuid>> {
    return withContext(Dispatchers.IO) {
        try {
            val jsonBody = Gson().toJson(emails)
            
            val request = Request.Builder()
                .url(API_URL + "get_uuids_from_emails/")
                .addHeader("Authorization", token)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
                
            val response = OkHttpClient().newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }
            
            val uuidStrings = Gson().fromJson(responseBody, Array<String>::class.java)
            val uuids = uuidStrings.map { createUuid(it) }
            Result.success(uuids)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la récupération des UUIDs: ${e.message}", e))
        }
    }
}

/**
 * Récupère les emails à partir des UUIDs
 * @param token Token d'authentification
 * @param uuids Liste des UUIDs
 * @return Liste des emails correspondants ou null en cas d'erreur
 */
suspend fun getEmailsFromUuids(token: String, uuids: List<Uuid>): Result<List<String>> {
    return withContext(Dispatchers.IO) {
        try {
            val uuidStrings = uuids.map { it.toString() }
            val jsonBody = Gson().toJson(uuidStrings)
            
            val request = Request.Builder()
                .url(API_URL + "get_emails_from_uuids/")
                .addHeader("Authorization", token)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
                
            val response = OkHttpClient().newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Réponse vide du serveur"))
            }
            
            val emails = Gson().fromJson(responseBody, Array<String>::class.java).toList()
            Result.success(emails)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la récupération des emails: ${e.message}", e))
        }
    }
}

/**
 * Récupère les mots de passe partagés par un utilisateur avec les emails des destinataires
 * @param token Token d'authentification
 * @param ownerUuid UUID du propriétaire
 * @return Liste des mots de passe partagés avec les emails ou null en cas d'erreur
 */
suspend fun getSharedByUserEmails(token: String, ownerUuid: Uuid): Result<List<SharedByUserEmail>> {
    return withContext(Dispatchers.IO) {
        try {
            val sharedByUserResult = getSharedByUser(token, ownerUuid)
            if (sharedByUserResult.isFailure) {
                return@withContext Result.failure(sharedByUserResult.exceptionOrNull() ?: Exception("Échec de la récupération des mots de passe partagés"))
            }
            
            val sharedByUser = sharedByUserResult.getOrNull()!!
            val result = mutableListOf<SharedByUserEmail>()
            
            for (shared in sharedByUser) {
                val recipientIds = shared.recipientIds.map { createUuid(it) }
                val emailsResult = getEmailsFromUuids(token, recipientIds)
                
                if (emailsResult.isFailure) {
                    Log.e(null, "Erreur lors de la récupération des emails: ${emailsResult.exceptionOrNull()?.message}")
                    continue
                }
                
                val emails = emailsResult.getOrNull()!!
                
                // Récupérer les statuts pour chaque partage
                val statuses = mutableListOf<ShareStatus>()
                
                for (recipientUuid in recipientIds) {
                    try {
                        val passUuid = createUuid(shared.passId)
                        val request = Request.Builder()
                            .url(API_URL + "get_shared_pass_status_json/" + 
                                ownerUuid.toString() + "/" + 
                                passUuid.toString() + "/" + 
                                recipientUuid.toString())
                            .addHeader("Authorization", token)
                            .build()
                            
                        val response = OkHttpClient().newCall(request).execute()
                        
                        if (!response.isSuccessful) {
                            statuses.add(ShareStatus.Pending) // Par défaut
                            continue
                        }
                        
                        val responseBody = response.body?.string()
                        if (responseBody.isNullOrEmpty()) {
                            statuses.add(ShareStatus.Pending) // Par défaut
                            continue
                        }
                        
                        val status = Gson().fromJson(responseBody, Int::class.java)
                        statuses.add(ShareStatus.values()[status])
                    } catch (e: Exception) {
                        Log.e(null, "Erreur lors de la récupération du statut: ${e.message}")
                        statuses.add(ShareStatus.Pending) // Par défaut en cas d'erreur
                    }
                }
                
                result.add(SharedByUserEmail(
                    passId = shared.passId,
                    emails = emails,
                    statuses = statuses
                ))
            }
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de la récupération des mots de passe partagés avec emails: ${e.message}", e))
        }
    }
}

/**
 * Rejette un mot de passe partagé
 * @param token Token d'authentification
 * @param recipientUuid UUID du destinataire
 * @param ownerUuid UUID du propriétaire du mot de passe
 * @param passUuid UUID du mot de passe
 * @return Résultat de l'opération
 */
suspend fun rejectSharedPass(
    token: String,
    recipientUuid: Uuid,
    ownerUuid: Uuid,
    passUuid: Uuid
): Result<Unit> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL + "reject_shared_pass_json/" + 
                    recipientUuid.toString() + "/" + 
                    ownerUuid.toString() + "/" + 
                    passUuid.toString())
                .addHeader("Authorization", token)
                .build()
                
            val response = OkHttpClient().newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors du rejet du mot de passe partagé: ${e.message}", e))
        }
    }
}

/**
 * Accepte un mot de passe partagé
 * @param token Token d'authentification
 * @param recipientUuid UUID du destinataire
 * @param ownerUuid UUID du propriétaire du mot de passe
 * @param passUuid UUID du mot de passe
 * @return Résultat de l'opération
 */
suspend fun acceptSharedPass(
    token: String,
    recipientUuid: Uuid,
    ownerUuid: Uuid,
    passUuid: Uuid
): Result<Unit> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL + "accept_shared_pass_json/" + 
                    recipientUuid.toString() + "/" + 
                    ownerUuid.toString() + "/" + 
                    passUuid.toString())
                .addHeader("Authorization", token)
                .build()
                
            val response = OkHttpClient().newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Erreur serveur: ${response.code} - ${response.message}"))
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Erreur lors de l'acceptation du mot de passe partagé: ${e.message}", e))
        }
    }
}