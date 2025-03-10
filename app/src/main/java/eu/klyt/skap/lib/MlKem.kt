package eu.klyt.skap.lib

import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import java.security.SecureRandom

class MlKem1024 {
    private val random = SecureRandom()
    private val params = MLKEMParameters.ml_kem_1024

    fun keygen(): Pair<ByteArray, ByteArray> {
        val keyPairGenerator = MLKEMKeyPairGenerator()
        keyPairGenerator.init(MLKEMKeyGenerationParameters(random, params))
        
        val keyPair: AsymmetricCipherKeyPair = keyPairGenerator.generateKeyPair()
        
        val publicKey = (keyPair.public as MLKEMPublicKeyParameters).encoded
        val privateKey = (keyPair.private as MLKEMPrivateKeyParameters).encoded
        
        return Pair(publicKey, privateKey)
    }

    fun encapsulate(recipientPublicKey: ByteArray): Pair<ByteArray, ByteArray> {
        val publicKeyParams = MLKEMPublicKeyParameters(params, recipientPublicKey)
        val kemGenerator = MLKEMGenerator(random)
        
        val encapsulationResult = kemGenerator.generateEncapsulated(publicKeyParams)
        val sharedSecret = encapsulationResult.secret
        val ciphertext = encapsulationResult.encapsulation
        
        return Pair(ciphertext, sharedSecret)
    }
    
    fun decapsulate(secretKey: ByteArray, ciphertext: ByteArray): Result<ByteArray> {
        val privateKeyParams = MLKEMPrivateKeyParameters(params, secretKey)
        val kemGenerator = MLKEMExtractor(privateKeyParams)
        val secret = kemGenerator.extractSecret(ciphertext)
        return if (secret == null) {
            Result.failure(Exception("Couldn't decapsulate the secret"))
        } else {
            Result.success(secret)
        }

    }
}


