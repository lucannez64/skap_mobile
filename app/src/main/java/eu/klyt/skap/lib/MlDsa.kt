package eu.klyt.skap.lib

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom

class MlDsa87 {
    private val random = SecureRandom()
    private val params = MLDSAParameters.ml_dsa_87

    fun keygen(): Pair<ByteArray, ByteArray> {
        val keyPairGenerator = MLDSAKeyPairGenerator()
        keyPairGenerator.init(MLDSAKeyGenerationParameters(random, params))

        val keyPair: AsymmetricCipherKeyPair = keyPairGenerator.generateKeyPair()

        val publicKey = (keyPair.public as MLDSAPublicKeyParameters).encoded
        val privateKey = (keyPair.private as MLDSAPrivateKeyParameters).encoded

        return Pair(publicKey, privateKey)
    }

    fun sign(privateKey: ByteArray, message: ByteArray): Result<ByteArray> {
        val privateKeyParams = MLDSAPrivateKeyParameters(params, privateKey)
        val signer = MLDSASigner()
        signer.init(true, privateKeyParams)
        signer.update(message, 0, message.size)
        val ciphertext = signer.generateSignature()
        return if (ciphertext == null) {
            Result.failure(Exception("Signature failed"))
        } else {
            Result.success(ciphertext)
        }

    }

    fun verify(publicKey: ByteArray, message: ByteArray,signature: ByteArray): Boolean {
        val publicKeyParams = MLDSAPublicKeyParameters(params, publicKey)
        val verifier = MLDSASigner()
        verifier.init(false, publicKeyParams)
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}


