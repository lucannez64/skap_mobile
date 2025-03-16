package eu.klyt.skap.lib

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.gosimple.nbvcxz.Nbvcxz
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.jcajce.provider.digest.SHA1
import org.bouncycastle.util.encoders.Hex
import java.io.IOException

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
    private val client = OkHttpClient()
    
    /**
     * Effectue une vérification complète de sécurité du mot de passe
     * @param password Le mot de passe à vérifier
     * @param language La langue pour les messages (fr ou en)
     * @return Le résultat de l'audit du mot de passe
     */
    suspend fun auditPassword(password: String, language: String): PasswordAuditResult {
        // Calculer la force du mot de passe
        val passwordStrengthEstimator = Nbvcxz()
        val result = passwordStrengthEstimator.estimate(password)
        val strength = result.entropy / 128.0 // Normaliser sur une échelle de 0 à 1 (128 bits est considéré très fort)
        
        // Vérifier si le mot de passe a été compromis
        val isCompromisedResult = checkCompromised(password)
        val isCompromised = isCompromisedResult > 0
        val compromisedCount = if (isCompromisedResult >= 0) isCompromisedResult else 0
        
        // Calculer le score final
        // Si le mot de passe a été compromis, on pénalise le score
        val score = if (isCompromised) {
            // Si compromis, le score maximum est de 0.5, et diminue en fonction du nombre de fuites
            val compromisedPenalty = minOf(0.5, (compromisedCount.toDouble() / 10.0))
            maxOf(0.0, 0.5 - compromisedPenalty)
        } else {
            // Si non compromis, le score est basé uniquement sur la force
            strength
        }
        
        // Messages selon la langue
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
        
        return PasswordAuditResult(
            strength = strength,
            isCompromised = isCompromised,
            compromisedCount = compromisedCount,
            score = score,
            strengthMessage = strengthMessage,
            securityMessage = securityMessage
        )
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
        // Auditer chaque mot de passe individuellement en parallèle
        val auditResults = mutableMapOf<String, PasswordAuditResult>()
        val uniquePasswords = mutableSetOf<String>()
        val duplicatedPasswords = mutableSetOf<String>()
        
        // Première passe - identifier les duplications (exécution séquentielle nécessaire)
        passwords.forEach { (_, password) ->
            if (password in uniquePasswords) {
                duplicatedPasswords.add(password)
            } else {
                uniquePasswords.add(password)
            }
        }
        
        // Deuxième passe - lancer les audits en parallèle
        val auditJobs = passwords.map { (id, password) ->
            async(Dispatchers.Default) {
                val result = auditPassword(password, language)
                id to result
            }
        }
        
        // Attendre que tous les audits soient terminés et collecter les résultats
        val auditPairs = auditJobs.awaitAll()
        auditPairs.forEach { (id, result) ->
            auditResults[id] = result
        }
        
        // Calculer les statistiques globales
        var totalScore = 0.0
        var compromisedCount = 0
        var weakCount = 0
        var mediumCount = 0
        var strongCount = 0
        
        auditResults.values.forEach { result ->
            totalScore += result.score
            
            if (result.isCompromised) {
                compromisedCount++
            }
            
            when {
                result.strength < 0.3 -> weakCount++
                result.strength < 0.6 -> mediumCount++
                else -> strongCount++
            }
        }
        
        // Calculer le score moyen
        val overallScore = if (auditResults.isNotEmpty()) {
            totalScore / auditResults.size
        } else {
            0.0
        }
        
        GlobalSecurityAuditResult(
            passwordResults = auditResults,
            overallScore = overallScore,
            compromisedCount = compromisedCount,
            weakCount = weakCount,
            mediumCount = mediumCount,
            strongCount = strongCount,
            duplicateCount = duplicatedPasswords.size
        )
    }
    
    /**
     * Vérifie si un mot de passe a été compromis en utilisant l'API haveibeenpwned
     * @param password Le mot de passe à vérifier
     * @return Le nombre de fois que le mot de passe a été compromis, 0 si non compromis, -1 en cas d'erreur
     */
    private suspend fun checkCompromised(password: String): Int = withContext(Dispatchers.IO) {
        try {
            // Calculer le hash SHA-1 du mot de passe
            val digest = SHA1.Digest()
            val passwordBytes = password.toByteArray(Charsets.UTF_8)
            digest.update(passwordBytes, 0, passwordBytes.size)
            val passwordHash = Hex.toHexString(digest.digest()).uppercase()
            
            // Diviser le hash en préfixe et suffixe
            val prefix = passwordHash.substring(0, 5)
            val suffix = passwordHash.substring(5)
            
            // Faire la requête à l'API haveibeenpwned
            val request = Request.Builder()
                .url("https://api.pwnedpasswords.com/range/$prefix")
                .header("User-Agent", "SKAP-Mobile-App")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("PasswordSecurity", "API request failed: ${response.code}")
                    return@withContext -1
                }
                
                val hashesText = response.body?.string() ?: ""
                val hashes = hashesText.split("\r\n", "\n")
                
                for (hash in hashes) {
                    val parts = hash.split(":")
                    if (parts.size == 2 && parts[0].equals(suffix, ignoreCase = true)) {
                        return@withContext parts[1].toInt()
                    }
                }
                
                return@withContext 0 // Mot de passe non trouvé dans la liste des compromis
            }
        } catch (e: IOException) {
            Log.e("PasswordSecurity", "Error checking password: ${e.message}")
            return@withContext -1 // Erreur lors de la vérification
        }
    }
} 