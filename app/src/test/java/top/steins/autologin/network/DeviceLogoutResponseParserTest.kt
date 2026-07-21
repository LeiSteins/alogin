package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DeviceLogoutResponseParserTest {

    @Test
    fun parse_jfselfSuccessPage_returnsSuccess() {
        val response = """
            <script type="text/javascript">
                ${'$'}(function () {
                    (function (msg) {
                        if (msg != "") {
                            swal({ text: msg })
                        }
                    })('成功');
                });
            </script>
        """.trimIndent()

        assertSame(DeviceLogoutResponse.Success, DeviceLogoutResponseParser.parse(response))
    }

    @Test
    fun parse_jfselfFailurePage_returnsServerMessage() {
        val response = """
            <script>
                (function (msg) {
                    if (msg != "") showMessage(msg);
                })('该设备不存在');
            </script>
        """.trimIndent()

        assertEquals(
            DeviceLogoutResponse.Failure("该设备不存在"),
            DeviceLogoutResponseParser.parse(response)
        )
    }

    @Test
    fun parse_jsonSuccess_remainsCompatible() {
        assertSame(
            DeviceLogoutResponse.Success,
            DeviceLogoutResponseParser.parse("{\"result\":true}")
        )
    }

    @Test
    fun parse_jsonFailure_remainsCompatible() {
        assertEquals(
            DeviceLogoutResponse.Failure(null),
            DeviceLogoutResponseParser.parse("callback({result: 0})")
        )
    }

    @Test
    fun parse_unknownResponse_returnsUnknown() {
        assertSame(
            DeviceLogoutResponse.Unknown,
            DeviceLogoutResponseParser.parse("<html><body>我的设备</body></html>")
        )
    }
}
