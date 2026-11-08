package com.example.vidoora

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.example.vidoora.feature.playback.VideoQualities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoQualitiesTest {
    private fun video(height: Int)=Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(height*16/9).setHeight(height).build()
    private fun group(id: String,formats: List<Format>,support: IntArray=IntArray(formats.size) { C.FORMAT_HANDLED })=
        Tracks.Group(TrackGroup(id,*formats.toTypedArray()),true,support,BooleanArray(formats.size))
    private val audio=TrackGroup("audio",Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())

    @Test fun choicesContainOnlySupportedVideoRenditionsSortedWithoutDuplicates() {
        val tracks=Tracks(listOf(group("video",listOf(video(720),video(360),video(480),video(-1),video(1080)),
            intArrayOf(C.FORMAT_HANDLED,C.FORMAT_HANDLED,C.FORMAT_HANDLED,C.FORMAT_HANDLED,C.FORMAT_EXCEEDS_CAPABILITIES)),
            group("same-resolution",listOf(video(360))),Tracks.Group(audio,false,intArrayOf(C.FORMAT_HANDLED),booleanArrayOf(true))))
        assertEquals(listOf("360p","480p","720p"),VideoQualities.available(tracks).map { it.label })
        assertEquals(1,VideoQualities.available(tracks).first().index)
    }
    @Test fun lowResolutionAndEmptySourcesDoNotInvent720p() {
        assertTrue(VideoQualities.available(Tracks.EMPTY).isEmpty())
        assertEquals(listOf("240p"),VideoQualities.available(Tracks(listOf(group("small",listOf(video(240)))))).map { it.label })
        assertTrue(VideoQualities.available(Tracks(listOf(group("unsupported",listOf(video(720)),intArrayOf(C.FORMAT_UNSUPPORTED_TYPE))))).isEmpty())
    }
    @Test fun manualSelectionPinsExactlyOneAvailableVideoTrack() {
        val video=group("video",listOf(video(360),video(480),video(720)))
        val tracks=Tracks(listOf(video))
        val base=TrackSelectionParameters.Builder().setPreferredAudioLanguage("hi").setMaxVideoSize(1280,720)
            .addOverride(TrackSelectionOverride(audio,0)).build()
        val selected=VideoQualities.parameters(base,tracks,480)
        assertEquals(listOf(1),selected.overrides[video.mediaTrackGroup]?.trackIndices)
        assertEquals(base.overrides[audio],selected.overrides[audio])
        assertEquals(base.preferredAudioLanguages,selected.preferredAudioLanguages)
        assertEquals(base.maxVideoHeight,selected.maxVideoHeight)
        assertEquals(2,selected.overrides.size)
    }
    @Test fun autoAndMissingQualityClearOnlyVideoOverride() {
        val video=group("video",listOf(video(360),video(720)))
        val tracks=Tracks(listOf(video))
        val manual=VideoQualities.parameters(TrackSelectionParameters.Builder().addOverride(TrackSelectionOverride(audio,0)).build(),tracks,720)
        for(height in listOf(null,1080)) {
            val auto=VideoQualities.parameters(manual,tracks,height)
            assertNull(auto.overrides[video.mediaTrackGroup]); assertNotNull(auto.overrides[audio]); assertEquals(1,auto.overrides.size)
        }
    }
    @Test fun applyingQualityDoesNotSeekPrepareReplaceMediaOrChangePauseState() {
        val tracks=Tracks(listOf(group("video",listOf(video(360),video(720)))))
        var parameters=TrackSelectionParameters.Builder().build()
        var updates=0
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) { _,method,args ->
            when(method.name) {
                "getTrackSelectionParameters" -> parameters
                "setTrackSelectionParameters" -> { parameters=args!![0] as TrackSelectionParameters; updates++; null }
                else -> throw AssertionError("Quality selection touched player state: ${method.name}")
            }
        } as Player
        VideoQualities.apply(player,tracks,360)
        VideoQualities.apply(player,tracks,360) // No repeated override when track events report the same state.
        assertEquals(1,updates)
        VideoQualities.apply(player,tracks,null); assertEquals(2,updates)
        assertTrue(parameters.overrides.isEmpty())
    }

    @Test fun freeLimitFiltersMenuClearsHighOverridesAndCapsAutoWhilePreservingAudio() {
        val video=group("video",listOf(video(360),video(480),video(720)))
        val tracks=Tracks(listOf(video))
        assertEquals(listOf(360,480),VideoQualities.available(tracks,480).map { it.height })
        val base=TrackSelectionParameters.Builder().addOverride(TrackSelectionOverride(audio,0))
            .addOverride(TrackSelectionOverride(video.mediaTrackGroup,2)).build()
        val limited=VideoQualities.parameters(base,tracks,720,480)
        assertEquals(480,limited.maxVideoHeight);assertNull(limited.overrides[video.mediaTrackGroup]);assertNotNull(limited.overrides[audio])
        assertEquals(listOf(360,480,720),VideoQualities.available(tracks,null).map { it.height })
    }
}
