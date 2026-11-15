package com.chdv.quickreceive

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). An empty salt means a salt of zeros, as the RFC says. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun newP256KeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        return g.generateKeyPair()
    }

    /** Coordinates as protobuf wants them: big-endian, two's complement (a leading 0 when the top bit is set). */
    fun publicXY(kp: KeyPair): Pair<ByteArray, ByteArray> {
        val w = (kp.public as ECPublicKey).w
        return w.affineX.toByteArray() to w.affineY.toByteArray()
    }

    /** ECDH on P-256 with the peer's x/y; returns the shared x coordinate (32 bytes). */
    fun ecdh(own: KeyPair, x: ByteArray, y: ByteArray): ByteArray {
        val params = (own.public as ECPublicKey).params
        val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(own.private)
        ka.doPhase(peer, true)
        val secret = ka.generateSecret()
        // Normalize to exactly 32 bytes, unsigned
        val mag = BigInteger(1, secret).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return if (mag.size < 32) ByteArray(32 - mag.size) + mag else mag
    }

    fun aesEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    fun aesDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    /** The 4-digit code that both phones show (same algorithm as Chromium's Nearby Share). */
    fun pinCode(authKey: ByteArray): String {
        var hash = 0
        var multiplier = 1
        for (b in authKey) {
            hash = (hash + b.toInt() * multiplier) % 9973
            multiplier = (multiplier * 31) % 9973
        }
        return String.format("%04d", Math.abs(hash))
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}

/** The four keys that protect a connection, from the receiver's (server's) point of view. */
class SessionKeys(val decrypt: ByteArray, val recvHmac: ByteArray, val encrypt: ByteArray, val sendHmac: ByteArray, val authKey: ByteArray) {
    companion object {
        private val D2D_SALT = hex("82AA55A0D397F88346CA1CEE8D3909B95F13FA7DEB1D4AB38376B8256DA85510")

        fun derive(sharedX: ByteArray, clientInit: ByteArray, serverInit: ByteArray): SessionKeys {
            val derivedSecret = Crypto.sha256(sharedX)
            val info = clientInit + serverInit
            val auth = Crypto.hkdf(derivedSecret, "UKEY2 v1 auth".toByteArray(), info, 32)
            val next = Crypto.hkdf(derivedSecret, "UKEY2 v1 next".toByteArray(), info, 32)
            val d2dClient = Crypto.hkdf(next, D2D_SALT, "client".toByteArray(), 32)
            val d2dServer = Crypto.hkdf(next, D2D_SALT, "server".toByteArray(), 32)
            val smSalt = Crypto.sha256("SecureMessage".toByteArray())
            return SessionKeys(
                decrypt = Crypto.hkdf(d2dClient, smSalt, "ENC:2".toByteArray(), 32),
                recvHmac = Crypto.hkdf(d2dClient, smSalt, "SIG:1".toByteArray(), 32),
                encrypt = Crypto.hkdf(d2dServer, smSalt, "ENC:2".toByteArray(), 32),
                sendHmac = Crypto.hkdf(d2dServer, smSalt, "SIG:1".toByteArray(), 32),
                authKey = auth,
            )
        }

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
