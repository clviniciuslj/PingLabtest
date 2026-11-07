package br.com.pingreal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubnetTest {
    @Test
    fun slash24HasOnlyUsableHostsWithoutTheOwnAddress() {
        val hosts = Subnet.hosts("192.168.1.20", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.20" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
    }

    @Test
    fun slash22CoversFourBlocks() {
        val hosts = Subnet.hosts("10.0.5.9", 22)
        assertEquals(1021, hosts.size)
        assertEquals("10.0.4.1", hosts.first())
        assertEquals("10.0.7.254", hosts.last())
    }

    @Test
    fun hugeNetworksAreScannedAsSlash24AndTinyOnesNotAtAll() {
        assertEquals(253, Subnet.hosts("172.16.9.40", 16).size)
        assertEquals("172.16.9.0/24", Subnet.label("172.16.9.40", 16))
        assertEquals(253, Subnet.hosts("192.168.0.5", null).size)
        assertTrue(Subnet.hosts("192.168.0.5", 31).isEmpty())
        assertTrue(Subnet.hosts("não é ip", 24).isEmpty())
    }

    @Test
    fun parsesAndFormatsAddresses() {
        assertEquals("192.168.1.1", Subnet.format(Subnet.parseIpv4("192.168.1.1")!!))
        assertNull(Subnet.parseIpv4("192.168.1"))
        assertNull(Subnet.parseIpv4("192.168.1.256"))
        assertNull(Subnet.parseIpv4("a.b.c.d"))
        assertEquals("192.168.1.0/24", Subnet.label("192.168.1.77", 24))
    }
}

class MacAndHintsTest {
    @Test
    fun readsProcNetArpSkippingIncompleteEntries() {
        val text = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.1.1      0x1         0x2         44:19:b6:11:22:33     *        wlan0
            192.168.1.9      0x1         0x0         00:00:00:00:00:00     *        wlan0
            192.168.1.30     0x1         0x2         ae:bb:cc:dd:ee:ff     *        wlan0
        """.trimIndent()
        val table = ArpTable.parseProcArp(text)
        assertEquals(2, table.size)
        assertEquals("44:19:B6:11:22:33", table["192.168.1.1"])
        assertFalse("192.168.1.9" in table)
    }

    @Test
    fun readsIpNeigh() {
        val text = """
            192.168.1.1 dev wlan0 lladdr 3c:ef:8c:aa:bb:cc REACHABLE
            192.168.1.50 dev wlan0  FAILED
            192.168.1.60 dev wlan0 lladdr 11:22:33:44:55:66 STALE
        """.trimIndent()
        val table = ArpTable.parseIpNeigh(text)
        assertEquals("3C:EF:8C:AA:BB:CC", table["192.168.1.1"])
        assertEquals("11:22:33:44:55:66", table["192.168.1.60"])
        assertFalse("192.168.1.50" in table)
    }

    @Test
    fun vendorAndPrivateMac() {
        assertEquals("Hikvision", Oui.vendor("44:19:b6:00:00:01"))
        assertEquals("Dahua", Oui.vendor("3C-EF-8C-00-00-01"))
        assertNull(Oui.vendor("12:34:56:00:00:01"))
        assertNull(Oui.vendor("lixo"))
        assertTrue(Oui.isPrivate("AE:BB:CC:DD:EE:FF"))
        assertFalse(Oui.isPrivate("44:19:B6:11:22:33"))
    }

    @Test
    fun cameraHintsComeFromPortsAndVendor() {
        assertTrue(CameraHints.reasons(listOf(80, 443), null).isEmpty())
        assertEquals(1, CameraHints.reasons(listOf(80, 554), null).size)
        assertEquals(2, CameraHints.reasons(listOf(554), "Hikvision").size)
        assertEquals(1, CameraHints.reasons(emptyList(), "Espressif").size)
        assertTrue(CameraHints.reasons(listOf(80), "TP-Link").isEmpty())
    }

    @Test
    fun nicknamesSurviveEncoding() {
        val names = linkedMapOf("AA:BB:CC:DD:EE:FF" to "ONU da sala", "192.168.1.7" to "Impressora\tnova")
        val back = DeviceNames.decode(DeviceNames.encode(names))
        assertEquals("ONU da sala", back["AA:BB:CC:DD:EE:FF"])
        assertEquals("Impressora nova", back["192.168.1.7"])
        assertTrue(DeviceNames.decode(null).isEmpty())
        assertEquals("AA:BB", DeviceNames.key("1.1.1.1", "AA:BB"))
        assertEquals("1.1.1.1", DeviceNames.key("1.1.1.1", null))
    }
}

private class NetFakePinger(private val alive: Set<String>) : Pinger {
    override fun ping(host: String, timeoutMs: Int) =
        if (host in alive) PingOutcome(true, 3, 64, PingFailure.NONE) else PingOutcome(false, null, null, PingFailure.TIMED_OUT)

    override fun cancelAll() {}
}

/** Rede falsa: cada host tem as portas que o teste definir; o resto não responde. */
private class NetFakeProber(private val states: Map<String, Map<Int, PortState>>) : PortProber {
    override fun probe(host: String, port: Int, timeoutMs: Int): PortState =
        states[host]?.get(port) ?: PortState.NO_RESPONSE
}

class NetworkScannerTest {
    private class Recorder : NetworkScanner.Listener {
        val devices = Collections.synchronizedList(ArrayList<Device>())
        val finished = CountDownLatch(1)
        @Volatile var cancelled: Boolean? = null
        @Volatile var lastProgress = 0

        override fun onProgress(done: Int, total: Int) {
            lastProgress = done
        }

        override fun onDevice(device: Device) {
            devices.add(device)
        }

        override fun onFinished(cancelled: Boolean) {
            this.cancelled = cancelled
            finished.countDown()
        }
    }

    @Test
    fun findsDevicesThatAnswerPingOrRefuseAConnection() {
        val prober = NetFakeProber(
            mapOf(
                "10.0.0.1" to mapOf(80 to PortState.OPEN, 554 to PortState.OPEN),
                "10.0.0.2" to mapOf(22 to PortState.CLOSED)
            )
        )
        val scanner = NetworkScanner(
            NetFakePinger(setOf("10.0.0.1", "10.0.0.4")), prober,
            arp = { mapOf("10.0.0.1" to "44:19:B6:00:00:01") },
            resolveName = { if (it == "10.0.0.1") "camera-sala" else null }
        )
        val rec = Recorder()
        scanner.scan(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4"), rec)

        assertTrue(rec.finished.await(10, TimeUnit.SECONDS))
        assertEquals(false, rec.cancelled)
        assertEquals(4, rec.lastProgress)

        val byIp = rec.devices.groupBy { it.ip }
        assertEquals(setOf("10.0.0.1", "10.0.0.2", "10.0.0.4"), byIp.keys) // .3 não existe
        val first = byIp.getValue("10.0.0.1")
        assertEquals(2, first.size) // achado, depois de novo com o MAC
        assertNull(first.first().mac)
        val enriched = first.last()
        assertEquals("44:19:B6:00:00:01", enriched.mac)
        assertEquals("Hikvision", enriched.vendor)
        assertEquals(listOf(80, 554), enriched.openPorts)
        assertEquals("camera-sala", enriched.hostname)
        assertEquals(3L, enriched.pingMs)
        assertEquals(2, enriched.cameraReasons.size)

        val refused = byIp.getValue("10.0.0.2").single()
        assertNull(refused.pingMs) // não respondeu ping, mas recusou a conexão
        assertTrue(refused.openPorts.isEmpty())
        assertTrue(refused.cameraReasons.isEmpty())
    }

    @Test
    fun emptyNetworkFinishesWithoutDevices() {
        val scanner = NetworkScanner(NetFakePinger(emptySet()), NetFakeProber(emptyMap()), arp = { emptyMap() })
        val rec = Recorder()
        scanner.scan(listOf("10.0.0.7", "10.0.0.8"), rec)
        assertTrue(rec.finished.await(10, TimeUnit.SECONDS))
        assertTrue(rec.devices.isEmpty())
        assertEquals(false, rec.cancelled)
    }

    @Test
    fun cancelReportsCancelled() {
        val slow = object : Pinger {
            override fun ping(host: String, timeoutMs: Int): PingOutcome {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                }
                return PingOutcome(true, 1, 64, PingFailure.NONE)
            }

            override fun cancelAll() {}
        }
        val scanner = NetworkScanner(slow, NetFakeProber(emptyMap()), arp = { emptyMap() })
        val rec = Recorder()
        scanner.scan((1..60).map { "10.0.1.$it" }, rec)
        Thread.sleep(100)
        scanner.cancel()
        assertTrue(rec.finished.await(10, TimeUnit.SECONDS))
        assertEquals(true, rec.cancelled)
        assertTrue(rec.devices.size < 60)
    }

    @Test
    fun scanPortsReportsEveryPortAndFinishes() {
        val prober = NetFakeProber(mapOf("10.0.0.9" to mapOf(80 to PortState.OPEN, 23 to PortState.CLOSED)))
        val scanner = NetworkScanner(NetFakePinger(emptySet()), prober)
        val results = java.util.concurrent.ConcurrentHashMap<Int, PortState>()
        val done = CountDownLatch(1)
        var cancelled: Boolean? = null
        scanner.scanPorts("10.0.0.9", Ports.ALL, { p, s -> results[p] = s }, { c -> cancelled = c; done.countDown() })
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(false, cancelled)
        assertEquals(Ports.ALL.size, results.size)
        assertEquals(PortState.OPEN, results[80])
        assertEquals(PortState.CLOSED, results[23])
        assertEquals(PortState.NO_RESPONSE, results[22])
    }
}
