package com.example.vidoora

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.vidoora.feature.playback.ThumbnailPreparation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ThumbnailPreparationTest {
    @Test fun galleryImageBecomesSmallLandscapeJpeg() {
        val input=ByteArrayOutputStream().also { Bitmap.createBitmap(800,1200,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        val jpeg=ThumbnailPreparation.prepare(input)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(jpeg,0,jpeg.size,bounds)
        assertEquals(480,bounds.outWidth);assertEquals(270,bounds.outHeight)
        assertTrue(jpeg.size<=256*1024);assertEquals(255,jpeg[0].toInt() and 255)
    }
    @Test fun invalidOrOversizedInputIsRejected() {
        for(bytes in listOf(byteArrayOf(),byteArrayOf(1,2,3),ByteArray(ThumbnailPreparation.MAX_INPUT+1))) {
            try { ThumbnailPreparation.prepare(bytes);fail("Invalid input accepted") } catch(_: IllegalArgumentException) { }
        }
    }
}
