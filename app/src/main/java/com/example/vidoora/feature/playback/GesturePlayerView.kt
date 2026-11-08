package com.example.vidoora.feature.playback

import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** Seek within known, seekable video bounds without changing its paused/playing state. */
object VideoSeeking {
    fun target(position: Long, duration: Long, forward: Boolean): Long? {
        if(duration<=0 || duration==C.TIME_UNSET) return null
        val current=position.coerceIn(0,duration)
        return if(forward) current+(duration-current).coerceAtMost(10_000L)
            else (current-10_000L).coerceAtLeast(0)
    }
    fun seek(player: Player,forward: Boolean): Boolean {
        if(player.isPlayingAd || !player.isCurrentMediaItemSeekable) return false
        val target=target(player.currentPosition,player.duration,forward) ?: return false
        player.seekTo(target)
        return true
    }
}

/** Keep native controller hit testing, accessibility, single taps and scrubbing intact. */
@UnstableApi
open class GesturePlayerView(context: Context) : PlayerView(context) {
    var onSeekFeedback: (Boolean)->Unit = {}
    private var consumingDoubleTap=false
    private val detector=GestureDetector(context,object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent)=true
        override fun onDoubleTap(event: MotionEvent): Boolean {
            val forward=event.x>=width/2f
            if(!seekDoubleTap(forward)) return false
            consumingDoubleTap=true
            onSeekFeedback(forward)
            return true
        }
    })
    protected open fun seekDoubleTap(forward: Boolean): Boolean = player?.let { VideoSeeking.seek(it,forward) } ?: false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(player?.isPlayingAd==true) return super.dispatchTouchEvent(event)
        // Never convert taps on a controller button or time bar into a seek gesture.
        if(!consumingDoubleTap && interactiveChildAt(this,event.rawX.toInt(),event.rawY.toInt())) {
            val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
            try { detector.onTouchEvent(cancel) } finally { cancel.recycle() }
            return super.dispatchTouchEvent(event)
        }
        detector.onTouchEvent(event)
        if(consumingDoubleTap) {
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) consumingDoubleTap=false
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    private fun interactiveChildAt(group: ViewGroup,x: Int,y: Int): Boolean {
        val rect=Rect()
        for(index in group.childCount-1 downTo 0) {
            val child=group.getChildAt(index)
            if(child.visibility!=View.VISIBLE || !child.getGlobalVisibleRect(rect) || !rect.contains(x,y)) continue
            if(child.isClickable || child.isFocusable) return true
            if(child is ViewGroup && interactiveChildAt(child,x,y)) return true
        }
        return false
    }
}



