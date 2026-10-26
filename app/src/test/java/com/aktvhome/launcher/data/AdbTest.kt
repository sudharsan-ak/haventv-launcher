package com.aktvhome.launcher.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.security.PublicKey
import java.security.Signature
import kotlin.concurrent.thread

class AdbTest {

    private lateinit var server: ServerSocket
    private val keys = AdbKey.loadOrCreate(Files.createTempDirectory("adbkey").toFile())

    @Before fun start() {
        server = ServerSocket(0)
    }

    @After fun stop() {
        server.close()
    }

    private fun verifies(pub: PublicKey, token: ByteArray, sig: ByteArray) = Signature.getInstance("NONEwithRSA").run {
        initVerify(pub)
        update(AdbKey.signedBytes(token))
        verify(sig)
    }

    /**
     * A fake adbd: [knows] says whether it already trusts the key; [allow] is your answer to the
     * "Allow debugging?" box. Shell commands are echoed back as output.
     */
    private fun fakeAdbd(knows: Boolean, allow: Boolean = true, tls: Boolean = false) = thread {
        server.accept().use { s ->
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val hello = AdbMessage.read(input)
            assertEquals(AdbMessage.CNXN, hello.command)
            if (tls) {
                AdbMessage(AdbMessage.STLS, 0x01000000, 0).write(out)
                return@thread
            }
            val token = ByteArray(20) { it.toByte() }
            AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token).write(out)
            val sig = AdbMessage.read(input)
            assertEquals(AdbMessage.AUTH_SIGNATURE, sig.arg0)
            if (!knows) {
                AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token).write(out)
                val pubMsg = AdbMessage.read(input)
                assertEquals(AdbMessage.AUTH_RSAPUBLICKEY, pubMsg.arg0)
                assertTrue(String(pubMsg.data).trimEnd('\u0000').endsWith(" haven@test"))
                // The key it offers is the one that signed.
                assertTrue(verifies(AdbKey.parseAndroidPublicKey(pubMsg.data), token, sig.data))
                if (!allow) return@thread
            } else {
                assertTrue(verifies(keys.public, token, sig.data))
            }
            AdbMessage(AdbMessage.CNXN, 0x01000000, 4096, "device::\u0000".toByteArray()).write(out)
            while (true) {
                val open = runCatching { AdbMessage.read(input) }.getOrNull() ?: return@thread
                assertEquals(AdbMessage.OPEN, open.command)
                val cmd = String(open.data).trimEnd('\u0000').removePrefix("shell:")
                val remote = 77
                AdbMessage(AdbMessage.OKAY, remote, open.arg0).write(out)
                AdbMessage(AdbMessage.WRTE, remote, open.arg0, "ran $cmd\n".toByteArray()).write(out)
                assertEquals(AdbMessage.OKAY, AdbMessage.read(input).command)
                AdbMessage(AdbMessage.CLSE, remote, open.arg0).write(out)
                assertEquals(AdbMessage.CLSE, AdbMessage.read(input).command)
            }
        }
    }

    @Test fun messageRoundTrip() {
        val buf = ByteArrayOutputStream()
        AdbMessage(AdbMessage.WRTE, 3, 4, "hi".toByteArray()).write(buf)
        assertEquals(26, buf.size())
        val m = AdbMessage.read(ByteArrayInputStream(buf.toByteArray()))
        assertEquals(AdbMessage.WRTE, m.command)
        assertEquals(3, m.arg0)
        assertEquals("hi", String(m.data))
    }

    @Test fun knownKeyRunsCommands() {
        val t = fakeAdbd(knows = true)
        var prompted = false
        val c = AdbConnection.connect("127.0.0.1", server.localPort, keys, "haven@test") { prompted = true }
        assertEquals("ran pm grant x\n", c.shell("pm grant x"))
        assertEquals("ran id\n", c.shell("id"))
        c.close()
        t.join(5_000)
        assertTrue(!prompted)
    }

    @Test fun newKeyAsksAndContinuesWhenAllowed() {
        val t = fakeAdbd(knows = false)
        var prompted = false
        val c = AdbConnection.connect("127.0.0.1", server.localPort, keys, "haven@test") { prompted = true }
        assertTrue(prompted)
        assertEquals("ran id\n", c.shell("id"))
        c.close()
        t.join(5_000)
    }

    @Test fun deniedIsAClearError() {
        val t = fakeAdbd(knows = false, allow = false)
        try {
            AdbConnection.connect("127.0.0.1", server.localPort, keys, "haven@test")
            fail("connected after Deny")
        } catch (e: AdbException) {
            assertTrue(e.message!!.contains("turned the connection down"))
        }
        t.join(5_000)
    }

    @Test fun wirelessDebuggingOnlyIsExplained() {
        val t = fakeAdbd(knows = true, tls = true)
        try {
            AdbConnection.connect("127.0.0.1", server.localPort, keys, "haven@test")
            fail("connected over TLS")
        } catch (e: AdbException) {
            assertTrue(e.message!!.contains("Wireless debugging"))
            // Cleanup tells Wireless-debugging-only TVs apart by the type, not the wording.
            assertTrue(e is AdbTlsRequired)
        }
        t.join(5_000)
    }

    @Test fun keyIsKeptBetweenRuns() {
        val dir = Files.createTempDirectory("adbkey2").toFile()
        val a = AdbKey.loadOrCreate(dir)
        val b = AdbKey.loadOrCreate(dir)
        assertEquals(a.public, b.public)
    }

    @Test(expected = java.net.ConnectException::class)
    fun nothingListeningMeansDebuggingIsOff() {
        val port = server.localPort
        server.close()
        AdbConnection.connect("127.0.0.1", port, keys, "haven@test")
    }
}
