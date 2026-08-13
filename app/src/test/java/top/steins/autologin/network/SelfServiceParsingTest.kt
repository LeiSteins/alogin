package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfServiceParsingTest {

    @Test
    fun findJsonObject_returnsBalancedObject() {
        val extraction = SelfServiceParsing.findJsonObject("callback({\"a\":1});")

        assertEquals(
            JsonObjectExtraction.Found("""{"a":1}"""),
            extraction
        )
    }

    @Test
    fun findJsonObject_skipsUntilMarkerAndHandlesNestedBraces() {
        val text = """prefix {"ignore":1} })( {"real":{"nested":true}} tail"""

        val extraction = SelfServiceParsing.findJsonObject(text, marker = "})(")

        assertEquals(
            JsonObjectExtraction.Found("""{"real":{"nested":true}}"""),
            extraction
        )
    }

    @Test
    fun findJsonObject_handlesEscapedQuotes() {
        val text = """{"a":"say \"hi\"","b":{"c":1}}"""

        assertEquals(
            JsonObjectExtraction.Found(text),
            SelfServiceParsing.findJsonObject(text)
        )
    }

    @Test
    fun findJsonObject_reportsMissingAndIncomplete() {
        assertEquals(
            JsonObjectExtraction.Missing,
            SelfServiceParsing.findJsonObject("no json here")
        )
        assertEquals(
            JsonObjectExtraction.Incomplete,
            SelfServiceParsing.findJsonObject("""{"a":1""")
        )
    }

    @Test
    fun extractCsrfToken_findsUuidAfterTokenMarker() {
        val html = """
            <input type="hidden" name="ajaxCsrfToken"
                value="a1b2c3d4-e5f6-7890-abcd-ef1234567890">
        """.trimIndent()

        assertEquals(
            "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            SelfServiceParsing.extractCsrfToken(html)
        )
    }

    @Test
    fun parseDeviceRows_normalizesMacAndMapsStatus() {
        val response = """
            {"rows":[
                ["0","AA-BB-CC-11-22-33","x","y","10.1.2.3"],
                ["1","dd:ee:ff:aa:bb:cc","x","y","10.1.2.4"]
            ]}
        """.trimIndent()

        val rows = SelfServiceParsing.parseDeviceRows(response)

        assertEquals(2, rows?.size)
        assertEquals("AABBCC112233", rows?.get(0)?.mac)
        assertEquals(false, rows?.get(0)?.isOnline)
        assertEquals("DDEEFFAABBCC", rows?.get(1)?.mac)
        assertEquals(true, rows?.get(1)?.isOnline)
    }

    @Test
    fun parseDeviceRows_returnsNullForMalformedJson() {
        assertNull(SelfServiceParsing.parseDeviceRows("not json"))
    }

    @Test
    fun parseDeviceRows_skipsRowsWithInvalidMac() {
        val response = """{"rows":[["1","not-a-mac","x","y","10.1.2.3"]]}"""

        assertEquals(emptyList<DeviceRow>(), SelfServiceParsing.parseDeviceRows(response))
    }

    @Test
    fun mergeDevices_overridesAccountEntriesAndSortsOnlineFirst() {
        val accountMacs = "AA-BB-CC-11-22-33;dd:ee:ff:aa:bb:cc"
        val rows = listOf(
            DeviceRow(mac = "AABBCC112233", status = "1", ipAddress = "10.1.2.3", isOnline = true)
        )

        val devices = SelfServiceParsing.mergeDevices(accountMacs, rows, "未知")

        assertEquals(2, devices.size)
        assertEquals("AA:BB:CC:11:22:33", devices[0].macAddress)
        assertTrue(devices[0].isOnline == true)
        assertEquals("10.1.2.3", devices[0].ipAddress)
        assertEquals("DD:EE:FF:AA:BB:CC", devices[1].macAddress)
        assertEquals("未知", devices[1].status)
        assertNull(devices[1].isOnline)
    }

    @Test
    fun canonicalMac_acceptsOnlyValidHexOfLengthTwelve() {
        assertEquals("AABBCC112233", SelfServiceParsing.canonicalMac("aa:bb:cc:11:22:33"))
        assertEquals("AABBCC112233", SelfServiceParsing.canonicalMac("AA-BB-CC-11-22-33"))
        assertNull(SelfServiceParsing.canonicalMac("aabbcc"))
        assertNull(SelfServiceParsing.canonicalMac("ggbbcc112233"))
    }

    @Test
    fun formatMac_groupsIntoColonSeparatedPairs() {
        assertEquals("AA:BB:CC:11:22:33", SelfServiceParsing.formatMac("AABBCC112233"))
    }

    @Test
    fun statusToOnlineState_mapsNumericAndTextValues() {
        assertEquals(true, SelfServiceParsing.statusToOnlineState("1"))
        assertEquals(false, SelfServiceParsing.statusToOnlineState("0"))
        assertEquals(true, SelfServiceParsing.statusToOnlineState("在线"))
        assertEquals(false, SelfServiceParsing.statusToOnlineState("离线"))
        assertNull(SelfServiceParsing.statusToOnlineState("unknown"))
    }

    @Test
    fun xorEncode_encodesEachCharacterWith0x16() {
        assertEquals("726427262622", SelfServiceParsing.xorEncode("dr1004"))
    }
}
