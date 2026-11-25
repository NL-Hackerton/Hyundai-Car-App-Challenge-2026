package ai.staygo

import ai.staygo.navi.NaviClient
import org.junit.Assert.assertEquals
import org.junit.Test

class NaviRouteTest {
    /** Go must hand the navi the same POI it is guiding to; an empty poiId makes the navi show an error dialog. */
    @Test fun goKeepsThePoiIdentityOfTheDestination() {
        val d = NaviClient.Destination(
            "부산시청", 35.1796, 129.0756, 375_000,
            poiId = "1234567", poiSubId = "0", address = "부산 연제구 중앙대로 1001", poiProvider = "n42",
            displayLatitude = 35.1799, displayLongitude = 129.0750,
        )
        val r = NaviClient.routeFor(d)
        assertEquals("부산시청", r.poiName)
        assertEquals("1234567", r.poiId)
        assertEquals("0", r.poiSubId)
        assertEquals("부산 연제구 중앙대로 1001", r.address)
        assertEquals("n42", r.poiProvider)
        assertEquals(35.1796, r.latitude, 1e-9)
        assertEquals(129.0756, r.longitude, 1e-9)
        assertEquals(35.1799, r.displayLatitude!!, 1e-9)
    }
}
