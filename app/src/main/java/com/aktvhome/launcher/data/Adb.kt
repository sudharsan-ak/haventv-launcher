package com.aktvhome.launcher.data

// A small client for this TV's own ADB (debugging) service on port 5555, so Haven can give itself
// the one-time grants without a computer. Plain Kotlin, unit tested against a fake adbd.
// The newer Wireless debugging (pairing codes, TLS) is a different protocol and isn't covered here.

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** One ADB message: a 24-byte header (command, two arguments, length, checksum, magic) and data. */
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0)) {

    fun write(out: OutputStream) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1)
            .putInt(data.size).putInt(checksum(data)).putInt(command xor -1)
        out.write(header.array())
        out.write(data)
        out.flush()
    }

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        fun checksum(data: ByteArray): Int = data.sumOf { it.toInt() and 0xff }

        fun read(input: InputStream): AdbMessage {
            val din = DataInputStream(input)
            val raw = ByteArray(24).also { din.readFully(it) }
            val h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val command = h.int
            val arg0 = h.int
            val arg1 = h.int
            val length = h.int
            h.int // checksum: TCP already guards the bytes, and newer adbd sends 0
            val magic = h.int
            if (magic != command xor -1) throw AdbException("Garbled reply from the TV's debugging service.")
            if (length < 0 || length > 1 shl 20) throw AdbException("Unexpected reply size from the TV's debugging service.")
            val data = ByteArray(length).also { din.readFully(it) }
            return AdbMessage(command, arg0, arg1, data)
        }
    }
}

open class AdbException(message: String) : Exception(message)

/** The TV answered with Wireless debugging's TLS, which Haven can't use yet. */
class AdbTlsRequired : AdbException("This TV only takes Wireless debugging, which Haven can't use yet.")

/**
 * Haven's ADB key: made once and kept in [dir]. The TV remembers it after you choose "Always
 * allow", so later runs don't ask again.
 */
object AdbKey {

    fun loadOrCreate(dir: File): KeyPair {
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        runCatching {
            val kf = KeyFactory.getInstance("RSA")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())),
            )
        }
        dir.mkdirs()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(pair.private.encoded)
        pub.writeBytes(pair.public.encoded)
        return pair
    }

    /** SHA-1's DigestInfo prefix: adbd checks the token signed as if it were a SHA-1 digest. */
    private val SHA1_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    fun sign(key: PrivateKey, token: ByteArray): ByteArray =
        Signature.getInstance("NONEwithRSA").run {
            initSign(key)
            update(SHA1_PREFIX + token)
            sign()
        }

    /** The prefix and token a signature covers, for checking one (tests, a fake adbd). */
    fun signedBytes(token: ByteArray): ByteArray = SHA1_PREFIX + token

    /**
     * The public key the way adbd wants it: Android's RSA layout (word count, n0inv, modulus and
     * R² mod n as little-endian words, exponent), base64, then a name and a NUL.
     */
    fun androidPublicKey(key: RSAPublicKey, name: String): ByteArray {
        val words = 2048 / 32
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(2048).pow(2).mod(n)
        val buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words).putInt(n0inv.toInt())
        littleEndianWords(n, words).forEach { buf.putInt(it) }
        littleEndianWords(rr, words).forEach { buf.putInt(it) }
        buf.putInt(key.publicExponent.toInt())
        return (Base64.getEncoder().encodeToString(buf.array()) + " " + name).toByteArray() + 0
    }

    /** Reads [androidPublicKey] back (tests, a fake adbd). */
    fun parseAndroidPublicKey(bytes: ByteArray): java.security.PublicKey {
        val b64 = String(bytes).trimEnd('\u0000').substringBefore(' ')
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)
        val words = buf.int
        buf.int
        val n = (0 until words).fold(BigInteger.ZERO) { acc, i ->
            acc.or(BigInteger.valueOf(buf.int.toLong() and 0xffffffffL).shiftLeft(32 * i))
        }
        repeat(words) { buf.int }
        val e = BigInteger.valueOf(buf.int.toLong())
        return KeyFactory.getInstance("RSA").generatePublic(java.security.spec.RSAPublicKeySpec(n, e))
    }

    private fun littleEndianWords(v: BigInteger, words: Int): List<Int> {
        val mask = BigInteger.valueOf(0xffffffffL)
        return (0 until words).map { i -> v.shiftRight(32 * i).and(mask).toInt() }
    }
}

/**
 * A connection to adbd. [connect] does the handshake: if the TV doesn't know Haven's key yet it
 * shows its "Allow debugging?" box and [onAllowPrompt] runs while it waits for your answer.
 */
class AdbConnection private constructor(private val socket: Socket) {

    private val input = socket.getInputStream().buffered()
    private val output = socket.getOutputStream()
    private var maxData = 4096
    private var nextId = 1

    /**
     * Runs [command] in the TV's shell and returns what it printed. Throws [AdbException] when the
     * TV won't open a shell for it.
     */
    fun shell(command: String): String {
        val local = nextId++
        AdbMessage(AdbMessage.OPEN, local, 0, "shell:$command".toByteArray() + 0).write(output)
        val out = StringBuilder()
        var remote = 0
        while (true) {
            val m = AdbMessage.read(input)
            // Only this command's messages: a late close from an earlier one would end this one unrun.
            if (m.arg1 != local) continue
            when (m.command) {
                AdbMessage.OKAY -> remote = m.arg0
                AdbMessage.WRTE -> {
                    out.append(String(m.data))
                    AdbMessage(AdbMessage.OKAY, local, m.arg0).write(output)
                }
                AdbMessage.CLSE -> {
                    // A refused OPEN closes before it's answered: the command never ran.
                    if (remote == 0) throw AdbException("The TV's debugging service wouldn't run the command.")
                    AdbMessage(AdbMessage.CLSE, local, remote).write(output)
                    return out.toString()
                }
            }
        }
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val VERSION = 0x01000000

        /**
         * Connects to [host]:[port]. Throws [java.net.ConnectException] when debugging is off (nothing
         * listening), [AdbTlsRequired] when the TV asks for Wireless debugging's TLS and [AdbException]
         * when it refuses.
         */
        fun connect(
            host: String,
            port: Int,
            keys: KeyPair,
            name: String,
            allowTimeoutMs: Int = 60_000,
            onAllowPrompt: () -> Unit = {},
        ): AdbConnection {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 3_000)
            socket.soTimeout = 10_000
            val c = AdbConnection(socket)
            try {
                c.handshake(keys, name, allowTimeoutMs, onAllowPrompt)
            } catch (e: Exception) {
                c.close()
                throw when (e) {
                    is java.net.SocketTimeoutException -> AdbException("The TV didn't answer in time.")
                    // adbd hangs up when you choose Deny, or don't answer.
                    is java.io.EOFException -> AdbException("The TV turned the connection down.")
                    else -> e
                }
            }
            return c
        }
    }

    private fun handshake(keys: KeyPair, name: String, allowTimeoutMs: Int, onAllowPrompt: () -> Unit) {
        AdbMessage(AdbMessage.CNXN, VERSION, maxData, "host::\u0000".toByteArray()).write(output)
        var signed = false
        while (true) {
            val m = AdbMessage.read(input)
            when (m.command) {
                AdbMessage.CNXN -> {
                    maxData = m.arg1
                    socket.soTimeout = 30_000
                    return
                }
                AdbMessage.STLS -> throw AdbTlsRequired()
                AdbMessage.AUTH -> {
                    if (m.arg0 != AdbMessage.AUTH_TOKEN) throw AdbException("The TV's debugging service asked for something unexpected.")
                    if (!signed) {
                        signed = true
                        AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_SIGNATURE, 0, AdbKey.sign(keys.private, m.data)).write(output)
                    } else {
                        // The TV doesn't know this key: offer it, and wait while the TV asks you.
                        val pub = AdbKey.androidPublicKey(keys.public as RSAPublicKey, name)
                        AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_RSAPUBLICKEY, 0, pub).write(output)
                        socket.soTimeout = allowTimeoutMs
                        onAllowPrompt()
                    }
                }
                else -> throw AdbException("The TV's debugging service answered unexpectedly.")
            }
        }
    }
}
