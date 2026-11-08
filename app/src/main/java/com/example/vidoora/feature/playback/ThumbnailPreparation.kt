package com.example.vidoora.feature.playback

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.ByteArrayOutputStream

object ThumbnailPreparation {
    const val MAX_INPUT=8*1024*1024
    fun fromUri(context: Context,uri: Uri): ByteArray {
        require(uri.scheme=="content")
        val data=ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer=ByteArray(8192)
            while(true) { val count=input.read(buffer); if(count<0) break; require(data.size()+count<=MAX_INPUT); data.write(buffer,0,count) }
        } ?: error("Cannot read image")
        return prepare(data.toByteArray())
    }
    fun prepare(bytes: ByteArray): ByteArray {
        require(bytes.size in 12..MAX_INPUT)
        val jpeg=bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte()
        val png=bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))
        val webp=bytes.copyOfRange(0,4).toString(Charsets.US_ASCII)=="RIFF" && bytes.copyOfRange(8,12).toString(Charsets.US_ASCII)=="WEBP"
        val gif=bytes.copyOfRange(0,6).toString(Charsets.US_ASCII) in setOf("GIF87a","GIF89a")
        val heif=bytes.copyOfRange(4,8).toString(Charsets.US_ASCII)=="ftyp" && bytes.copyOfRange(8,12).toString(Charsets.US_ASCII) in setOf("heic","heix","hevc","hevx","mif1","msf1","avif")
        require(jpeg || png || webp || gif || heif) { "Choose a supported image" }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong()*bounds.outHeight<=64_000_000)
        var sample=1
        while(bounds.outWidth/sample>960 || bounds.outHeight/sample>540) sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Invalid image")
        val output=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888)
        try {
            val scale=maxOf(480f/bitmap.width,270f/bitmap.height)
            val width=bitmap.width*scale; val height=bitmap.height*scale
            Canvas(output).apply {
                drawColor(Color.BLACK)
                drawBitmap(bitmap,null,RectF((480-width)/2,(270-height)/2,(480+width)/2,(270+height)/2),Paint(Paint.FILTER_BITMAP_FLAG))
            }
            return ByteArrayOutputStream().also { check(output.compress(Bitmap.CompressFormat.JPEG,85,it)) }.toByteArray().also { require(it.size<=256*1024) }
        } finally { bitmap.recycle();output.recycle() }
    }
}
