package com.example.vidoora.feature.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

@UnstableApi
data class VideoQuality(val height: Int,val group: TrackGroup,val index: Int) {
    val label: String get()="${height}p"
}

/** Select only supported video tracks advertised by the loaded HLS source. */
@UnstableApi
object VideoQualities {
    fun available(tracks: Tracks,maxHeight: Int?=null): List<VideoQuality> = tracks.groups
        .filter { it.type==C.TRACK_TYPE_VIDEO }
        .flatMap { group -> (0 until group.length).mapNotNull { index ->
            val height=group.getTrackFormat(index).height
            if(group.isTrackSupported(index) && height>0 && (maxHeight==null || height<=maxHeight)) VideoQuality(height,group.mediaTrackGroup,index) else null
        } }.distinctBy { it.height }.sortedBy { it.height }

    fun apply(player: Player,tracks: Tracks,height: Int?,maxHeight: Int?=null) {
        val selected=parameters(player.trackSelectionParameters,tracks,height,maxHeight)
        if(selected!=player.trackSelectionParameters) player.trackSelectionParameters=selected
    }

    fun parameters(base: TrackSelectionParameters,tracks: Tracks,height: Int?,maxHeight: Int?=null): TrackSelectionParameters {
        val builder=base.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if(maxHeight!=null) builder.setMaxVideoSize(Int.MAX_VALUE,maxHeight)
        available(tracks,maxHeight).firstOrNull { it.height==height }?.let { option ->
            builder.setOverrideForType(TrackSelectionOverride(option.group,option.index))
        }
        return builder.build()
    }
}

