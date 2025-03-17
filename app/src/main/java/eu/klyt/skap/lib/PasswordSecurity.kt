package eu.klyt.skap.lib

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.gosimple.nbvcxz.Nbvcxz
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.jcajce.provider.digest.SHA1
import org.bouncycastle.util.encoders.Hex
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class PasswordAuditResult(
    val strength: Double, // Valeur entre 0 et 1
    val isCompromised: Boolean,
    val compromisedCount: Int,
    val score: Double, // Score final normalisé entre 0 et 1
    val strengthMessage: String,
    val securityMessage: String
)

data class GlobalSecurityAuditResult(
    val passwordResults: Map<String, PasswordAuditResult>, // Map avec identifiant et résultat
    val overallScore: Double, // Score global entre 0 et 1
    val compromisedCount: Int, // Nombre total de mots de passe compromis
    val weakCount: Int, // Nombre total de mots de passe faibles
    val mediumCount: Int, // Nombre total de mots de passe de force moyenne
    val strongCount: Int, // Nombre total de mots de passe forts
    val duplicateCount: Int // Nombre de mots de passe dupliqués
)

class PasswordSecurityAuditor {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    
    private val passwordStrengthEstimator = Nbvcxz()
    private val compromisedCache = ConcurrentHashMap<String, Int>()
    private val auditCache = ConcurrentHashMap<String, PasswordAuditResult>()
    private val hashCache = ConcurrentHashMap<String, String>()
    private val maxConcurrentRequests = 5
    private val requestCounter = AtomicInteger(0)
    
    /**
     * Effectue une vérification complète de sécurité du mot de passe
     * @param password Le mot de passe à vérifier
     * @param language La langue pour les messages (fr ou en)
     * @return Le résultat de l'audit du mot de passe
     */
    suspend fun auditPassword(password: String, language: String): PasswordAuditResult {
        // Vérifier le cache
        val cacheKey = "$password:$language"
        auditCache[cacheKey]?.let { return it }
        
        // Calculer la force du mot de passe
        val result = passwordStrengthEstimator.estimate(password)
        val strength = result.entropy / 128.0
        
        // Vérifier si le mot de passe a été compromis
        val isCompromisedResult = checkCompromised(password)
        val isCompromised = isCompromisedResult > 0
        val compromisedCount = if (isCompromisedResult >= 0) isCompromisedResult else 0
        
        val score = calculateScore(strength, isCompromised, compromisedCount)
        
        val (strengthMessage, securityMessage) = generateMessages(
            strength, isCompromised, compromisedCount, language
        )
        
        val auditResult = PasswordAuditResult(
            strength = strength,
            isCompromised = isCompromised,
            compromisedCount = compromisedCount,
            score = score,
            strengthMessage = strengthMessage,
            securityMessage = securityMessage
        )
        
        // Mettre en cache le résultat
        auditCache[cacheKey] = auditResult
        return auditResult
    }
    
    private fun calculateScore(strength: Double, isCompromised: Boolean, compromisedCount: Int): Double {
        return if (isCompromised) {
            val compromisedPenalty = minOf(0.5, (compromisedCount.toDouble() / 10.0))
            maxOf(0.0, 0.5 - compromisedPenalty)
        } else {
            strength
        }
    }
    
    private fun generateMessages(
        strength: Double,
        isCompromised: Boolean,
        compromisedCount: Int,
        language: String
    ): Pair<String, String> {
        val strengthMessage = when {
            strength < 0.3 -> if (language == "fr") "Faible" else "Weak"
            strength < 0.6 -> if (language == "fr") "Moyen" else "Medium"
            else -> if (language == "fr") "Fort" else "Strong"
        }
        
        val securityMessage = if (isCompromised) {
            if (language == "fr")
                "Ce mot de passe a été compromis dans $compromisedCount fuites de données."
            else
                "This password has been compromised in $compromisedCount data breaches."
        } else {
            if (language == "fr")
                "Ce mot de passe n'a pas été trouvé dans les fuites de données connues."
            else
                "This password hasn't been found in known data breaches."
        }
        
        return Pair(strengthMessage, securityMessage)
    }
    
    /**
     * Effectue un audit de sécurité global sur tous les mots de passe fournis
     * @param passwords Map avec identifiant et mot de passe
     * @param language La langue pour les messages (fr ou en)
     * @return Le résultat global de l'audit de sécurité
     */
    suspend fun auditAllPasswords(
        passwords: Map<String, String>,
        language: String
    ): GlobalSecurityAuditResult = coroutineScope {
        val auditResults = mutableMapOf<String, PasswordAuditResult>()
        val uniquePasswords = mutableSetOf<String>()
        val duplicatedPasswords = mutableSetOf<String>()
        
        // Identifier les duplications
        passwords.values.forEach { password ->
            if (password in uniquePasswords) {
                duplicatedPasswords.add(password)
            } else {
                uniquePasswords.add(password)
            }
        }
        
        // Pré-calculer les hashes pour tous les mots de passe uniques
        uniquePasswords.forEach { password ->
            hashCache.getOrPut(password) {
                calculatePasswordHash(password)
            }
        }
        
        // Lancer les audits en parallèle avec limitation
        val auditJobs = passwords.map { (id, password) ->
            async(Dispatchers.Default) {
                val result = auditPassword(password, language)
                id to result
            }
        }
        
        val auditPairs = auditJobs.awaitAll()
        auditPairs.forEach { (id, result) ->
            auditResults[id] = result
        }
        
        // Calculer les statistiques globales de manière optimisée
        val stats = auditResults.values.fold(Stats()) { acc, result ->
            acc.copy(
                totalScore = acc.totalScore + result.score,
                compromisedCount = acc.compromisedCount + if (result.isCompromised) 1 else 0,
                weakCount = acc.weakCount + if (result.strength < 0.3) 1 else 0,
                mediumCount = acc.mediumCount + if (result.strength in 0.3..0.6) 1 else 0,
                strongCount = acc.strongCount + if (result.strength >= 0.6) 1 else 0
            )
        }
        
        val overallScore = if (auditResults.isNotEmpty()) {
            stats.totalScore / auditResults.size
        } else {
            0.0
        }
        
        GlobalSecurityAuditResult(
            passwordResults = auditResults,
            overallScore = overallScore,
            compromisedCount = stats.compromisedCount,
            weakCount = stats.weakCount,
            mediumCount = stats.mediumCount,
            strongCount = stats.strongCount,
            duplicateCount = duplicatedPasswords.size
        )
    }
    
    private suspend fun checkCompromised(password: String): Int = withContext(Dispatchers.IO) {
        // Vérifier le cache
        compromisedCache[password]?.let { return@withContext it }
        
        // Attendre si trop de requêtes en cours
        while (requestCounter.get() >= maxConcurrentRequests) {
            Thread.sleep(100)
        }
        
        try {
            requestCounter.incrementAndGet()
            
            val passwordHash = hashCache.getOrPut(password) {
                calculatePasswordHash(password)
            }
            val (prefix, suffix) = splitHash(passwordHash)
            
            val request = Request.Builder()
                .url("https://api.pwnedpasswords.com/range/$prefix")
                .header("User-Agent", "SKAP-Mobile-App")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("PasswordSecurity", "API request failed: ${response.code}")
                    return@withContext -1
                }
                
                val count = response.body?.string()
                    ?.split("\r\n", "\n")
                    ?.firstOrNull { it.startsWith(suffix, ignoreCase = true) }
                    ?.split(":")
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 0
                
                // Mettre en cache le résultat
                compromisedCache[password] = count
                return@withContext count
            }
        } catch (e: IOException) {
            Log.e("PasswordSecurity", "Error checking password: ${e.message}")
            return@withContext -1
        } finally {
            requestCounter.decrementAndGet()
        }
    }
    
    private fun calculatePasswordHash(password: String): String {
        val digest = SHA1.Digest()
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        digest.update(passwordBytes, 0, passwordBytes.size)
        return Hex.toHexString(digest.digest()).uppercase()
    }
    
    private fun splitHash(hash: String): Pair<String, String> {
        return Pair(hash.substring(0, 5), hash.substring(5))
    }
    
    private data class Stats(
        val totalScore: Double = 0.0,
        val compromisedCount: Int = 0,
        val weakCount: Int = 0,
        val mediumCount: Int = 0,
        val strongCount: Int = 0
    )
} 