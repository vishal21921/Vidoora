package com.example.vidoora.feature.ads

/** Content-timeline cues scoped to one player, not an app/account clock. No identifying input. */
object VideoAdSchedule {
    const val INTERVAL_MS=600_000L
    const val MAX_DURATION_MS=7L*24*60*60*1000
    const val SAMPLE_TAG="https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_preroll_skippable&sz=640x480&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&correlator="
    /** A single SDK video-ad break, explicitly armed by the actual-watch clock. Never an entry pre-roll. */
    fun breakXml(): String = """
        <vmap:VMAP xmlns:vmap="http://www.iab.net/videosuite/vmap" version="1.0">
        <vmap:AdBreak timeOffset="start" breakType="linear" breakId="viewing-break">
        <vmap:AdSource id="demo" allowMultipleAds="false" followRedirects="true">
        <vmap:AdTagURI templateType="vast3"><![CDATA[$SAMPLE_TAG${java.util.concurrent.ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE)}]]></vmap:AdTagURI>
        </vmap:AdSource></vmap:AdBreak></vmap:VMAP>
    """.trimIndent()
}
