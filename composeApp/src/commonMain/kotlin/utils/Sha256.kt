package utils

object Sha256 {
    /**
     * 计算字符串的 SHA-256 哈希值（返回十六进制字符串）
     */
    fun hash(input: String): String {
        return hash(input.encodeToByteArray())
    }

    /**
     * 计算字节数组的 SHA-256 哈希值（返回十六进制字符串）
     */
    fun hash(input: ByteArray): String {
        val result = sha256(input)
        return result.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    private fun sha256(message: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19
        )

        val k = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
            0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
            0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
        )

        val len = message.size * 8L
        val tmp = message.copyOf(message.size + 1 + (64 - (message.size + 1 + 8) % 64) % 64 + 8)
        tmp[message.size] = 0x80.toByte()
        
        for (i in 0 until 8) {
            tmp[tmp.size - 1 - i] = ((len ushr (i * 8)) and 0xff).toByte()
        }

        val w = IntArray(64)
        for (i in 0 until tmp.size step 64) {
            for (j in 0 until 16) {
                w[j] = ((tmp[i + j * 4].toInt() and 0xff) shl 24) or
                        ((tmp[i + j * 4 + 1].toInt() and 0xff) shl 16) or
                        ((tmp[i + j * 4 + 2].toInt() and 0xff) shl 8) or
                        (tmp[i + j * 4 + 3].toInt() and 0xff)
            }
            for (j in 16 until 64) {
                val s0 = (w[j - 15] rotor 7) xor (w[j - 15] rotor 18) xor (w[j - 15] ushr 3)
                val s1 = (w[j - 2] rotor 17) xor (w[j - 2] rotor 19) xor (w[j - 2] ushr 10)
                w[j] = w[j - 16] + s0 + w[j - 7] + s1
            }

            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hTemp = h[7]

            for (j in 0 until 64) {
                val s1 = (e rotor 6) xor (e rotor 11) xor (e rotor 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hTemp + s1 + ch + k[j] + w[j]
                val s0 = (a rotor 2) xor (a rotor 13) xor (a rotor 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                hTemp = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hTemp
        }

        val result = ByteArray(32)
        for (i in 0 until 8) {
            result[i * 4] = (h[i] ushr 24).toByte()
            result[i * 4 + 1] = (h[i] ushr 16).toByte()
            result[i * 4 + 2] = (h[i] ushr 8).toByte()
            result[i * 4 + 3] = h[i].toByte()
        }
        return result
    }

    private infix fun Int.rotor(n: Int): Int = (this ushr n) or (this shl (32 - n))
}
