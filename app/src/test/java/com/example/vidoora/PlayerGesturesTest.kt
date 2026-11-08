package com.example.vidoora

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Player
import com.example.vidoora.feature.playback.GesturePlayerView
import com.example.vidoora.feature.playback.VideoSeeking
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PlayerGesturesTest {
    @Test fun seekClampsAtBothEndsAndRejectsUnknownDuration() {
        assertEquals(0L,VideoSeeking.target(3_000,60_000,false))
        assertEquals(60_000L,VideoSeeking.target(57_000,60_000,true))
        assertEquals(17_000L,VideoSeeking.target(7_000,60_000,true))
        assertEquals(7_000L,VideoSeeking.target(17_000,60_000,false))
        assertNull(VideoSeeking.target(0,C.TIME_UNSET,true))
        assertNull(VideoSeeking.target(0,0,false))
    }
    @Test fun doubleTapSeekDoesNotChangePauseSpeedQualityOrMediaSource() {
        val calls=mutableListOf<String>()
        var position=15_000L
        var seekable=true
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) { _,method,args ->
            when(method.name) {
                "isPlayingAd" -> false
                "isCurrentMediaItemSeekable" -> seekable
                "getCurrentPosition" -> position
                "getDuration" -> 60_000L
                "seekTo" -> { calls+=method.name; position=args!![0] as Long; null }
                else -> { calls+=method.name; null }
            }
        } as Player
        assertTrue(VideoSeeking.seek(player,true)); assertEquals(25_000L,position)
        assertTrue(VideoSeeking.seek(player,false)); assertEquals(15_000L,position)
        seekable=false
        assertFalse(VideoSeeking.seek(player,true))
        assertEquals(listOf("seekTo","seekTo"),calls)
    }
    @Test fun doubleTapCannotSeekThroughAnAdOrConsumeItsControls() {
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) { _,method,_ ->
            when(method.name) { "isPlayingAd" -> true; else -> error("Ad seek must not call ${method.name}") }
        } as Player
        assertFalse(VideoSeeking.seek(player,true))
        assertFalse(VideoSeeking.seek(player,false))
    }
    @Test fun nativeDoubleTapsUseLeftAndRightWhileButtonTapsRemainClicks() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=controller.get()
            val seeks=mutableListOf<Boolean>()
            val view=object: GesturePlayerView(activity) {
                override fun seekDoubleTap(forward: Boolean): Boolean { seeks+=forward; return true }
            }
            activity.setContentView(view)
            view.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1000,600)
            fun tap(x: Float,time: Long) {
                for((action,offset) in listOf(MotionEvent.ACTION_DOWN to 0L,MotionEvent.ACTION_UP to 20L)) {
                    val event=MotionEvent.obtain(time,time+offset,action,x,100f,0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            tap(100f,1000); assertTrue(seeks.isEmpty()); tap(100f,1100)
            tap(900f,2000); tap(900f,2100)
            assertEquals(listOf(false,true),seeks)
            val overlay=view.findViewById<FrameLayout>(androidx.media3.ui.R.id.exo_overlay)
            var clicks=0
            val button=Button(activity).apply { setOnClickListener { clicks++ } }
            overlay.addView(button,FrameLayout.LayoutParams(300,200))
            view.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1000,600)
            tap(100f,3000); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            tap(100f,3100); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(2,clicks)
            assertEquals(listOf(false,true),seeks)
        } finally { controller.pause().stop().destroy() }
    }
}


