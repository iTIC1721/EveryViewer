package com.tabpdf.export

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The standard security handler (ISO 32000-2, 7.6.4) for files that open without a password: those
 * encrypted only to limit printing, copying or editing. Additions to such a file are encrypted with
 * the file's key, so it keeps its limits. RC4 and AES-128 (revisions 2–4) and AES-256 (5–6).
 */
class PdfCrypt private constructor(
    private val key: ByteArray,
    private val revision: Int,
    private val streams: Method,
    private val strings: Method,
) {
    enum class Method { None, Rc4, Aes128, Aes256 }

    fun encryptStream(num: Int, gen: Int, data: ByteArray) = encrypt(streams, num, gen, data)
    fun decryptStream(num: Int, gen: Int, data: ByteArray) = decrypt(streams, num, gen, data)
    fun encryptString(num: Int, gen: Int, data: ByteArray) = encrypt(strings, num, gen, data)

    private fun objectKey(method: Method, num: Int, gen: Int): ByteArray {
        if (method == Method.Aes256) return key
        // Algorithm 1: the file key with the object's number and generation.
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(key)
        md5.update(byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), gen.toByte(), (gen shr 8).toByte()))
        if (method == Method.Aes128) md5.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54)) // "sAlT"
        return md5.digest().copyOf(minOf(key.size + 5, 16))
    }

    private fun encrypt(method: Method, num: Int, gen: Int, data: ByteArray): ByteArray = when (method) {
        Method.None -> data
        Method.Rc4 -> rc4(objectKey(method, num, gen), data)
        Method.Aes128, Method.Aes256 -> {
            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(objectKey(method, num, gen), "AES"), IvParameterSpec(iv))
            iv + cipher.doFinal(data)
        }
    }

    private fun decrypt(method: Method, num: Int, gen: Int, data: ByteArray): ByteArray = when (method) {
        Method.None -> data
        Method.Rc4 -> rc4(objectKey(method, num, gen), data)
        Method.Aes128, Method.Aes256 -> {
            if (data.size < 32 || data.size % 16 != 0) throw PdfSyntaxException("Bad encrypted data")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(objectKey(method, num, gen), "AES"), IvParameterSpec(data, 0, 16))
            cipher.doFinal(data, 16, data.size - 16)
        }
    }

    companion object {
        private val PAD = byteArrayOf(
            0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(), 0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
        )

        /** The handler for [pdf]'s /Encrypt, or null if it needs a password (or isn't the standard one). */
        fun forFile(pdf: PdfFile): PdfCrypt? {
            val dict = pdf.resolve(pdf.trailer["Encrypt"]) as? Map<*, *> ?: return null
            if ((dict["Filter"] as? PdfName)?.text != "Standard") return null
            fun int(name: String) = (pdf.resolve(dict[name]) as? PdfNumber)?.value?.toInt()
            fun bytes(name: String) = (pdf.resolve(dict[name]) as? PdfString)?.bytes
            val v = int("V") ?: 0
            val r = int("R") ?: return null
            val o = bytes("O") ?: return null
            val u = bytes("U") ?: return null
            val p = int("P") ?: return null
            val encryptMetadata = pdf.resolve(dict["EncryptMetadata"]) != false
            val id0 = ((pdf.resolve(pdf.trailer["ID"]) as? List<*>)?.firstOrNull()?.let { pdf.resolve(it) } as? PdfString)?.bytes ?: ByteArray(0)

            fun filterMethod(name: String): Method? {
                if (v < 4) return Method.Rc4
                val filterName = (dict[name] as? PdfName)?.text ?: "Identity"
                if (filterName == "Identity") return Method.None
                val cf = (pdf.resolve(dict["CF"]) as? Map<*, *>)?.get(filterName)?.let { pdf.resolve(it) } as? Map<*, *> ?: return null
                return when ((cf["CFM"] as? PdfName)?.text) {
                    "V2" -> Method.Rc4
                    "AESV2" -> Method.Aes128
                    "AESV3" -> Method.Aes256
                    "None" -> Method.None
                    else -> null
                }
            }
            val streams = filterMethod("StmF") ?: return null
            val strings = filterMethod("StrF") ?: return null

            val key = if (r >= 5) {
                val ue = bytes("UE") ?: return null
                if (u.size < 48 || ue.size < 32) return null
                // Empty user password: check it against U, then unwrap the key from UE.
                if (!hash(r, ByteArray(0), u.copyOfRange(32, 40)).contentEquals(u.copyOfRange(0, 32))) return null
                val intermediate = hash(r, ByteArray(0), u.copyOfRange(40, 48))
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(intermediate, "AES"), IvParameterSpec(ByteArray(16)))
                cipher.doFinal(ue, 0, 32)
            } else {
                // Algorithm 2 with the empty password.
                val length = if (r == 2) 5 else ((int("Length") ?: 40) / 8).coerceIn(5, 16)
                val md5 = MessageDigest.getInstance("MD5")
                md5.update(PAD)
                md5.update(o.copyOf(32))
                md5.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
                md5.update(id0)
                if (r >= 4 && !encryptMetadata) md5.update(byteArrayOf(-1, -1, -1, -1))
                var k = md5.digest()
                if (r >= 3) repeat(50) { k = MessageDigest.getInstance("MD5").digest(k.copyOf(length)) }
                k = k.copyOf(length)
                // Algorithms 4 and 5: the empty password is the user password only if U matches.
                val expected = if (r == 2) rc4(k, PAD) else {
                    val m = MessageDigest.getInstance("MD5")
                    m.update(PAD)
                    m.update(id0)
                    var x = rc4(k, m.digest())
                    for (i in 1..19) x = rc4(ByteArray(k.size) { (k[it].toInt() xor i).toByte() }, x)
                    x
                }
                val compare = if (r == 2) 32 else 16
                if (u.size < compare || !expected.copyOf(compare).contentEquals(u.copyOf(compare))) return null
                k
            }
            return PdfCrypt(key, r, streams, strings)
        }

        /** Algorithm 2.A/2.B: SHA-256 for revision 5, the hardened hash for 6 (no user key data: the user password). */
        private fun hash(revision: Int, password: ByteArray, salt: ByteArray): ByteArray {
            var k = MessageDigest.getInstance("SHA-256").digest(password + salt)
            if (revision < 6) return k
            var round = 0
            while (true) {
                val k1 = ByteArray(64 * (password.size + k.size))
                val unit = password + k
                for (i in 0 until 64) System.arraycopy(unit, 0, k1, i * unit.size, unit.size)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, 0, 16, "AES"), IvParameterSpec(k, 16, 16))
                val e = cipher.doFinal(k1)
                var sum = 0
                for (i in 0 until 16) sum += e[i].toInt() and 0xFF
                k = MessageDigest.getInstance(
                    when (sum % 3) {
                        0 -> "SHA-256"
                        1 -> "SHA-384"
                        else -> "SHA-512"
                    }
                ).digest(e)
                round++
                if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
            }
            return k.copyOf(32)
        }

        fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            val out = ByteArray(data.size)
            var i = 0
            j = 0
            for (n in data.indices) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
                out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
            return out
        }
    }
}
