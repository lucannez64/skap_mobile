package eu.klyt.skap.lib
import org.bouncycastle.crypto.digests.Blake3Digest

fun blake3(input: ByteArray): ByteArray {
    val digest = Blake3Digest(32)
    digest.update(input, 0, input.size)
    val hash = ByteArray(digest.digestSize)
    digest.doFinal(hash, 0)
    return hash
}
