package com.github.k1rakishou.chan4captchasolver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import androidx.core.graphics.withTranslation
import com.github.k1rakishou.chan4captchasolver.data.CaptchaInfo
import java.util.LinkedList
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

@OptIn(ExperimentalUnsignedTypes::class)
object Helpers {
  private const val TAG = "Helpers"
  private val captchaBgColor = 0xFFEEEEEE.toInt()

  @OptIn(ExperimentalTime::class)
  suspend fun combineBgWithFgWithBestDisorder(
    captchaInfo: CaptchaInfo,
    customOffset: Float?,
  ): ResultImageData {
    val (resultImageData, duration) = measureTimedValue {
      coroutineScope {
        combineBgWithFgWithBestDisorderInternal(
          coroutineScope = this,
          captchaInfo = captchaInfo,
          customOffset = customOffset
        )
      }
    }

    Log.d(TAG, "combineBgWithFgWithBestDisorder() took ${duration}")

    return resultImageData
  }

  private suspend fun combineBgWithFgWithBestDisorderInternal(
    coroutineScope: CoroutineScope,
    captchaInfo: CaptchaInfo,
    customOffset: Float?
  ): ResultImageData {
    val paint = Paint().apply {
      flags = Paint.ANTI_ALIAS_FLAG
      color = captchaBgColor
      style = Paint.Style.FILL
    }

    val bgPixelsArgb = captchaInfo.bgPixelsArgb
    val fgPixelsArgb = captchaInfo.fgPixelsArgb!!

    var bestDisorder = 999f
    var bestImagePixels: IntArray? = null
    var bestOffset = customOffset?.toInt() ?: -1

    val width = captchaInfo.fgBitmapPainter!!.intrinsicSize.width.toInt()
    val height = captchaInfo.fgBitmapPainter!!.intrinsicSize.height.toInt()
    val th = 80
    val pw = 16
    val scale = th / height
    val canvasHeight = width * scale + pw * 2
    val canvasWidth = th

    val bgWidthDiff = canvasHeight - width
    val halfBgWidthDiff = bgWidthDiff / 2f

    val resultBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
    var bgBitmap: Bitmap? = null
    val fgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val resultPixels = IntArray(resultBitmap.width * resultBitmap.height)

    val offsets = if (customOffset != null) {
      IntProgression.fromClosedRange(-customOffset.toInt(), -customOffset.toInt(), 1)
    } else {
      0 downTo -(captchaInfo.widthDiff())
    }

    for (offset in offsets) {
      coroutineScope.ensureActive()
      val canvas = Canvas(resultBitmap)

      // Fill the whole canvas with the captcha bg color (0xFFEEEEEE)
      canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)

      // Flip the image horizontally and then rotate it. I don't understand why we need to do this
      // either. Probably the model was trained this way.
      canvas.scale(-scale.toFloat(), scale.toFloat())
      canvas.rotate(90f)

      if (bgPixelsArgb != null) {
        // Draw the background image in the center of the canvas
        canvas.withTranslation(x = halfBgWidthDiff) {
          canvas.withTranslation(x = offset.toFloat()) {
            kotlin.run {
              val bitmap = Bitmap.createBitmap(captchaInfo.bgWidth!!, height, Bitmap.Config.ARGB_8888)
              bgBitmap = bitmap

              bitmap.setPixels(bgPixelsArgb, 0, captchaInfo.bgWidth!!, 0, 0, captchaInfo.bgWidth!! - bgWidthDiff, height)
              canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
          }
        }
      }

      // Draw the foreground image in the center of the canvas
      canvas.withTranslation(x = halfBgWidthDiff) {
        kotlin.run {
          fgBitmap.setPixels(fgPixelsArgb, 0, width, 0, 0, width, height)
          canvas.drawBitmap(fgBitmap, 0f, 0f, null)
        }
      }

      kotlin.run {
        // Fill the leftmost part of the captcha image with the captcha bg color (0xFFEEEEEE).
        // This will remove all the noise which helps with character recognition
        canvas.drawRect(
          0f,
          0f,
          halfBgWidthDiff,
          resultBitmap.width.toFloat(),
          paint
        )

        // The same but for the rightmost part of the captcha image
        canvas.drawRect(
          resultBitmap.height.toFloat() - (bgWidthDiff / 2f),
          0f,
          resultBitmap.height.toFloat(),
          resultBitmap.width.toFloat(),
          paint
        )
      }

      resultBitmap.getPixels(resultPixels, 0, resultBitmap.width, 0, 0, resultBitmap.width, resultBitmap.height)
      val disorder = calculateDisorder(resultPixels, resultBitmap.width, resultBitmap.height)

      if (disorder < bestDisorder) {
        bestDisorder = disorder
        bestImagePixels = resultPixels.clone()
        bestOffset = offset
      }
    }

    Log.d(TAG, "combineBgWithFgWithBestDisorder() bestOffset=${bestOffset}, bestDisorder=${bestDisorder}")

    val resultImageData = ResultImageData(
      bestOffset = -bestOffset,
      width = resultBitmap.width,
      height = resultBitmap.height,
      bestImagePixels = bestImagePixels!!
    )

    resultBitmap.recycle()
    fgBitmap.recycle()
    bgBitmap?.recycle()

    return resultImageData
  }

  class ResultImageData(
    val bestOffset: Int?,
    val width: Int,
    val height: Int,
    val bestImagePixels: IntArray
  )

  private fun calculateDisorder(imagePixelsInput: IntArray, width: Int, height: Int): Float {
    val totalCount = width * height
    val pic = Array<Byte>(totalCount) { 0 }
    val visited = Array<Int>(totalCount) { 0 }
    val imagePixels = imagePixelsInput.toUIntArray()

    for (idx in 0 until totalCount) {
      if (visited[idx] > 0) {
        continue
      }

      if (!black(imagePixels[idx])) {
        continue
      }

      var blackCount = 0
      val items = mutableListOf<Int>()
      val toVisit = LinkedList<Int>()
      toVisit.push(idx)

      while (toVisit.isNotEmpty()) {
        val cc = toVisit.pop()

        if (visited[cc] > 0) {
          continue
        }
        visited[cc] = 1

        if (black(imagePixels[cc])) {
          items += cc
          blackCount++

          toVisit.push(cc + 1)
          toVisit.push(cc - 1)
          toVisit.push(cc + width)
          toVisit.push(cc - width)
        }
      }

      if (blackCount >= 24) {
        items.forEach { x -> pic[x] = 1 }
      }
    }

    var res = 0
    var total = 0

    for (idx in 0 until (width * height - width)) {
      if (pic[idx] != pic[idx + width]) {
        res += 1
      }

      if (pic[idx] > 0) {
        total += 1
      }
    }

    if (total == 0) {
      total = 1
    }

    return res.toFloat() / total.toFloat()
  }

  private fun black(x: UInt): Boolean {
    // Get the R channel of ARGB
    val r = ((x shr 16) and 0xFFu)

    return r < 64u
  }

}