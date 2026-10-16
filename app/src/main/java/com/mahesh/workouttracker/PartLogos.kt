package com.mahesh.workouttracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.LinearLayout

/** v3.12 anatomy body-part logos: one 228×228 JPEG per part in
 *  assets/body-part-logos/logo_<partId>.jpg (part ids in [BodyParts.all]
 *  match the logo suffixes exactly). Derived from OpenStax, Anatomy and
 *  Physiology (2016), chart "Anterior and Posterior Views of Muscles"
 *  via Wikimedia Commons, CC BY 4.0 — labels removed, non-target muscles
 *  desaturated, target group kept in full red via region masks. See
 *  research/body-part-logos/PROVENANCE.md; attribution also appears in
 *  Settings. Decoded lazily and cached in a HashMap (same asset-decode +
 *  cache pattern as [PhotoLib]); a missing/corrupt logo returns null so
 *  callers simply omit the image and render exactly as before. */
object PartLogos {
    private val cache = HashMap<String, Bitmap>()

    fun bitmap(context: Context, partId: String): Bitmap? {
        cache[partId]?.let { return it }
        return try {
            val bmp = context.assets.open("body-part-logos/logo_$partId.jpg").use { BitmapFactory.decodeStream(it) }
            if (bmp != null) cache[partId] = bmp
            bmp
        } catch (e: Exception) { null }
    }

    fun imageView(context: Context, partId: String, sizeDp: Int): ImageView? {
        val bmp = bitmap(context, partId) ?: return null
        return ImageView(context).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(10)
            layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
        }
    }
}
