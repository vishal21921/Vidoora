package com.vidoora.backend.modules.playback

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Decode only bounded JPEGs; re-encoding strips metadata and prevents arbitrary file storage. */
object ThumbnailImages {
    const val MAX_BYTES=256*1024
    fun sanitize(bytes: ByteArray): ByteArray {
        try { return normalize(bytes) }
        catch(e: IllegalArgumentException) { throw e }
        catch(_: Exception) { throw IllegalArgumentException("Invalid JPEG thumbnail") }
    }
    private fun normalize(bytes: ByteArray): ByteArray {
        require(bytes.size in 4..MAX_BYTES && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte()) { "Choose a JPEG thumbnail up to 256 KiB" }
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers=ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Invalid thumbnail" }
            val reader=readers.next()
            try {
                require(reader.formatName.equals("JPEG",true)) { "JPEG required" }
                reader.input=input
                require(reader.getWidth(0) in 1..480 && reader.getHeight(0) in 1..270) { "Thumbnail must fit 480 × 270" }
                val decoded=reader.read(0) ?: throw IllegalArgumentException("Invalid thumbnail")
                val clean=BufferedImage(decoded.width,decoded.height,BufferedImage.TYPE_INT_RGB)
                clean.createGraphics().let { graphics -> try { graphics.drawImage(decoded,0,0,null) } finally { graphics.dispose() } }
                return ByteArrayOutputStream().also { require(ImageIO.write(clean,"jpeg",it)) }.toByteArray().also { require(it.size<=MAX_BYTES) }
            } finally { reader.dispose() }
        }
    }
}
