package com.gazi

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BmiChartBand(
  val category: BmiCategory,
  val rangeTextRes: Int,
  val minBmi: Float,
  val maxBmi: Float,
)

object BmiChartData {
  const val SCALE_MIN = 12.0f
  const val SCALE_MAX = 45.0f

  val bands: List<BmiChartBand> =
    listOf(
      BmiChartBand(
        category = BmiCategory.UNDERWEIGHT,
        rangeTextRes = R.string.about_cat_underweight_range,
        minBmi = 12.0f,
        maxBmi = 18.5f,
      ),
      BmiChartBand(
        category = BmiCategory.NORMAL,
        rangeTextRes = R.string.about_cat_normal_range,
        minBmi = 18.5f,
        maxBmi = 25.0f,
      ),
      BmiChartBand(
        category = BmiCategory.OVERWEIGHT,
        rangeTextRes = R.string.about_cat_overweight_range,
        minBmi = 25.0f,
        maxBmi = 30.0f,
      ),
      BmiChartBand(
        category = BmiCategory.OBESITY_CLASS_1,
        rangeTextRes = R.string.about_cat_obesity_class_1_range,
        minBmi = 30.0f,
        maxBmi = 35.0f,
      ),
      BmiChartBand(
        category = BmiCategory.OBESITY_CLASS_2,
        rangeTextRes = R.string.about_cat_obesity_class_2_range,
        minBmi = 35.0f,
        maxBmi = 40.0f,
      ),
      BmiChartBand(
        category = BmiCategory.OBESITY_CLASS_3,
        rangeTextRes = R.string.about_cat_obesity_class_3_range,
        minBmi = 40.0f,
        maxBmi = 45.0f,
      ),
    )
}

object BmiShareHelper {
  private const val SHARE_DIR = "shared_bmi"
  private const val IMAGE_WIDTH = 1080

  suspend fun shareBmiResultImage(
    context: Context,
    calculation: BmiCalculation,
  ): Result<Unit> {
    return runCatching {
      val file =
        withContext(Dispatchers.IO) {
          val bitmap = renderBmiResultBitmap(context, calculation)
          try {
            saveBitmapToCache(context, bitmap)
          } finally {
            bitmap.recycle()
          }
        }
      val uri: Uri =
        FileProvider.getUriForFile(
          context,
          "${context.packageName}.fileprovider",
          file,
        )
      launchShareSheet(context, uri, calculation)
    }
  }

  internal fun saveBitmapToCache(context: Context, bitmap: Bitmap): File {
    val dir = File(context.cacheDir, SHARE_DIR)
    if (!dir.exists()) {
      dir.mkdirs()
    } else {
      dir.listFiles()?.forEach { existing ->
        runCatching { existing.delete() }
      }
    }
    val outFile = File(dir, "bmi_result_${System.currentTimeMillis()}.png")
    FileOutputStream(outFile).use { stream ->
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
        error("Failed to compress BMI result image.")
      }
    }
    return outFile
  }

  private fun launchShareSheet(
    context: Context,
    contentUri: Uri,
    calculation: BmiCalculation,
  ) {
    val shareText =
      context.getString(
        R.string.share_bmi_text_format,
        BmiFormatters.formatBmi(calculation.bmi),
        BmiFormatters.categoryLabel(context, calculation.category),
      )
    val sendIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, contentUri)
        putExtra(Intent.EXTRA_TEXT, shareText)
        clipData = ClipData.newRawUri(null, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    val chooserIntent =
      Intent.createChooser(
          sendIntent,
          context.getString(R.string.share_bmi_chooser_title),
        )
        .apply {
          if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(chooserIntent)
  }

  internal fun renderBmiResultBitmap(
    context: Context,
    calculation: BmiCalculation,
  ): Bitmap {
    val detailRows = buildList {
      add(
        context.getString(R.string.healthy_bmi_range_label) to
          context.getString(R.string.healthy_bmi_range_value)
      )
      add(
        context.getString(R.string.healthy_weight_for_height_label) to
          BmiFormatters.formatHealthyWeightRange(calculation)
      )
      add(
        context.getString(R.string.healthy_weight_status_label) to
          BmiFormatters.formatWeightSuggestion(context, calculation)
      )
      add(
        context.getString(R.string.label_height) to
          BmiFormatters.formatHeight(calculation)
      )
      add(
        context.getString(R.string.label_weight) to
          BmiFormatters.formatWeight(calculation)
      )
      if (calculation.age != null) {
        add(
          context.getString(R.string.label_age) to
            "${calculation.age} ${context.getString(R.string.suffix_yrs)}"
        )
      }
      if (calculation.gender != GenderOption.UNSPECIFIED) {
        add(
          context.getString(R.string.label_gender) to
            BmiFormatters.genderLabel(context, calculation.gender)
        )
      }
    }

    val outerMargin = 52f
    val headerHeight = 148f
    val cardGap = 28f
    val heroCardHeight = 360f
    val chartHeaderHeight = 112f
    val chartRowHeight = 76f
    val chartBottomPad = 24f
    val chartCardHeight =
      chartHeaderHeight + (BmiChartData.bands.size * chartRowHeight) + chartBottomPad
    val detailsHeaderPad = 36f
    val detailRowHeight = 62f
    val detailsCardHeight = detailsHeaderPad + (detailRows.size * detailRowHeight) + 20f
    val footerHeight = 90f

    val totalHeight =
      (headerHeight +
          cardGap +
          heroCardHeight +
          cardGap +
          chartCardHeight +
          cardGap +
          detailsCardHeight +
          footerHeight)
        .toInt()

    val bitmap = createBitmap(IMAGE_WIDTH, totalHeight)
    val canvas = Canvas(bitmap)

    val bgColor = "#F3FAF9".toColorInt()
    val cardBgColor = Color.WHITE
    val cardBorderColor = "#D3E7E5".toColorInt()
    val primaryColor = "#0D7A82".toColorInt()
    val primaryContainerColor = "#D2F1EE".toColorInt()
    val onPrimaryContainerColor = "#063E44".toColorInt()
    val textPrimary = "#0F1E22".toColorInt()
    val textSecondary = "#4B6368".toColorInt()
    val trackBgColor = "#EAF5F4".toColorInt()
    val dividerColor = "#E2EFEF".toColorInt()
    val categoryColor = colorIntForCategory(calculation.category)

    canvas.drawColor(bgColor)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val strokePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = cardBorderColor
      }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 1. Top Header Bar
    fillPaint.color = primaryColor
    canvas.drawRect(0f, 0f, IMAGE_WIDTH.toFloat(), headerHeight, fillPaint)

    textPaint.apply {
      color = Color.WHITE
      textSize = 42f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.LEFT
    }
    canvas.drawText(context.getString(R.string.app_name), outerMargin, 72f, textPaint)

    textPaint.apply {
      color = "#D2F1EE".toColorInt()
      textSize = 24f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    canvas.drawText(
      BmiFormatters.formatTimestamp(calculation.timestampMillis),
      outerMargin,
      110f,
      textPaint,
    )

    val badgeLabel = context.getString(R.string.share_card_header_badge)
    textPaint.apply {
      textSize = 22f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }
    val badgeTextW = textPaint.measureText(badgeLabel)
    val badgeRect =
      RectF(
        IMAGE_WIDTH - outerMargin - badgeTextW - 44f,
        46f,
        IMAGE_WIDTH - outerMargin,
        98f,
      )
    fillPaint.color = primaryContainerColor
    canvas.drawRoundRect(badgeRect, 18f, 18f, fillPaint)
    textPaint.color = onPrimaryContainerColor
    canvas.drawText(badgeLabel, badgeRect.centerX(), badgeRect.centerY() + 8f, textPaint)

    var cursorY = headerHeight + cardGap
    val cardLeft = outerMargin
    val cardRight = IMAGE_WIDTH - outerMargin

    // 2. Hero Score & Spectrum Scale Card
    val heroRect = RectF(cardLeft, cursorY, cardRight, cursorY + heroCardHeight)
    fillPaint.color = cardBgColor
    canvas.drawRoundRect(heroRect, 28f, 28f, fillPaint)
    canvas.drawRoundRect(heroRect, 28f, 28f, strokePaint)

    val bmiText = BmiFormatters.formatBmi(calculation.bmi)
    textPaint.apply {
      color = categoryColor
      textSize = 96f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }
    canvas.drawText(bmiText, heroRect.centerX(), cursorY + 114f, textPaint)

    val catLabel = BmiFormatters.categoryLabel(context, calculation.category)
    textPaint.apply {
      textSize = 28f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }
    val catTextW = textPaint.measureText(catLabel)
    val catBadgeRect =
      RectF(
        heroRect.centerX() - (catTextW / 2f) - 28f,
        cursorY + 138f,
        heroRect.centerX() + (catTextW / 2f) + 28f,
        cursorY + 194f,
      )
    fillPaint.color = withAlpha(categoryColor, 36)
    canvas.drawRoundRect(catBadgeRect, 16f, 16f, fillPaint)
    val catStroke =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = withAlpha(categoryColor, 100)
      }
    canvas.drawRoundRect(catBadgeRect, 16f, 16f, catStroke)
    textPaint.color = categoryColor
    canvas.drawText(catLabel, catBadgeRect.centerX(), catBadgeRect.centerY() + 10f, textPaint)

    // Spectrum Bar inside Hero Card
    val barLeft = cardLeft + 44f
    val barRight = cardRight - 44f
    val barTop = cursorY + 244f
    val barBottom = barTop + 26f
    val barWidth = barRight - barLeft
    val scaleSpan = BmiChartData.SCALE_MAX - BmiChartData.SCALE_MIN
    val gap = 6f

    BmiChartData.bands.forEachIndexed { idx, band ->
      val startFrac = (band.minBmi - BmiChartData.SCALE_MIN) / scaleSpan
      val endFrac = (band.maxBmi - BmiChartData.SCALE_MIN) / scaleSpan
      val segLeft = barLeft + (startFrac * barWidth) + if (idx == 0) 0f else gap / 2f
      val segRight =
        barLeft + (endFrac * barWidth) - if (idx == BmiChartData.bands.lastIndex) 0f else gap / 2f
      fillPaint.color = colorIntForCategory(band.category)
      canvas.drawRoundRect(RectF(segLeft, barTop, segRight, barBottom), 10f, 10f, fillPaint)
    }

    val clampedBmi = calculation.bmi.toFloat().coerceIn(BmiChartData.SCALE_MIN, BmiChartData.SCALE_MAX)
    val markerX =
      (barLeft + ((clampedBmi - BmiChartData.SCALE_MIN) / scaleSpan) * barWidth)
        .coerceIn(barLeft + 18f, barRight - 18f)
    val markerY = (barTop + barBottom) / 2f
    fillPaint.color = Color.WHITE
    canvas.drawCircle(markerX, markerY, 20f, fillPaint)
    fillPaint.color = categoryColor
    canvas.drawCircle(markerX, markerY, 13f, fillPaint)

    val ticks = listOf(12.0f to "12", 18.5f to "18.5", 25.0f to "25", 30.0f to "30", 35.0f to "35", 40.0f to "40", 45.0f to "45")
    textPaint.apply {
      color = textSecondary
      textSize = 22f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    ticks.forEachIndexed { idx, (value, label) ->
      val frac = (value - BmiChartData.SCALE_MIN) / scaleSpan
      textPaint.textAlign =
        when (idx) {
          0 -> Paint.Align.LEFT
          ticks.lastIndex -> Paint.Align.RIGHT
          else -> Paint.Align.CENTER
        }
      canvas.drawText(label, barLeft + frac * barWidth, barBottom + 42f, textPaint)
    }

    cursorY += heroCardHeight + cardGap

    // 3. BMI Chart Card
    val chartRect = RectF(cardLeft, cursorY, cardRight, cursorY + chartCardHeight)
    fillPaint.color = cardBgColor
    canvas.drawRoundRect(chartRect, 28f, 28f, fillPaint)
    canvas.drawRoundRect(chartRect, 28f, 28f, strokePaint)

    textPaint.apply {
      color = textPrimary
      textSize = 34f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.LEFT
    }
    canvas.drawText(context.getString(R.string.bmi_chart_title), cardLeft + 36f, cursorY + 52f, textPaint)

    textPaint.apply {
      color = textSecondary
      textSize = 23f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    canvas.drawText(
      context.getString(R.string.bmi_chart_subtitle),
      cardLeft + 36f,
      cursorY + 86f,
      textPaint,
    )

    var rowTop = cursorY + chartHeaderHeight
    val chartContentLeft = cardLeft + 32f
    val chartContentRight = cardRight - 32f

    textPaint.apply {
      textSize = 24f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val maxCategoryWidth =
      BmiChartData.bands.maxOf { band ->
        textPaint.measureText(BmiFormatters.categoryLabel(context, band.category))
      }

    textPaint.apply {
      textSize = 23f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val maxRangeWidth =
      BmiChartData.bands.maxOf { band ->
        textPaint.measureText(context.getString(band.rangeTextRes))
      }

    val trackLeft = chartContentLeft + 44f + maxCategoryWidth + 24f
    val trackRight = (chartContentRight - 16f - maxRangeWidth - 24f).coerceAtLeast(trackLeft + 140f)
    val trackW = trackRight - trackLeft

    BmiChartData.bands.forEach { band ->
      val isActive = band.category == calculation.category
      val bandColor = colorIntForCategory(band.category)
      val rowRect = RectF(chartContentLeft, rowTop, chartContentRight, rowTop + chartRowHeight - 10f)

      if (isActive) {
        fillPaint.color = withAlpha(bandColor, 26)
        canvas.drawRoundRect(rowRect, 16f, 16f, fillPaint)
        val activeBorder =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = withAlpha(bandColor, 110)
          }
        canvas.drawRoundRect(rowRect, 16f, 16f, activeBorder)
      }

      val centerY = rowRect.centerY()
      fillPaint.color = bandColor
      canvas.drawCircle(chartContentLeft + 22f, centerY, 9f, fillPaint)

      textPaint.apply {
        color = if (isActive) textPrimary else textSecondary
        textSize = 24f
        typeface =
          Typeface.create(Typeface.DEFAULT, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
      }
      canvas.drawText(
        BmiFormatters.categoryLabel(context, band.category),
        chartContentLeft + 44f,
        centerY + 8f,
        textPaint,
      )

      // Stepped BMI Chart Bar Track
      val miniBarRect = RectF(trackLeft, centerY - 9f, trackRight, centerY + 9f)
      fillPaint.color = trackBgColor
      canvas.drawRoundRect(miniBarRect, 9f, 9f, fillPaint)

      val sFrac = (band.minBmi - BmiChartData.SCALE_MIN) / scaleSpan
      val eFrac = (band.maxBmi - BmiChartData.SCALE_MIN) / scaleSpan
      val zoneRect =
        RectF(
          trackLeft + sFrac * trackW,
          centerY - 9f,
          trackLeft + eFrac * trackW,
          centerY + 9f,
        )
      fillPaint.color = if (isActive) bandColor else withAlpha(bandColor, 190)
      canvas.drawRoundRect(zoneRect, 9f, 9f, fillPaint)

      if (isActive) {
        val dotX =
          (trackLeft + ((clampedBmi - BmiChartData.SCALE_MIN) / scaleSpan) * trackW)
            .coerceIn(zoneRect.left + 8f, zoneRect.right - 8f)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(dotX, centerY, 12f, fillPaint)
        fillPaint.color = bandColor
        canvas.drawCircle(dotX, centerY, 7.5f, fillPaint)
      }

      textPaint.apply {
        color = if (isActive) bandColor else textSecondary
        textSize = 23f
        typeface =
          Typeface.create(Typeface.DEFAULT, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
      }
      canvas.drawText(
        context.getString(band.rangeTextRes),
        chartContentRight - 16f,
        centerY + 8f,
        textPaint,
      )

      rowTop += chartRowHeight
    }

    cursorY += chartCardHeight + cardGap

    // 4. Healthy Weight & Measurements Summary Card
    val detailsRect = RectF(cardLeft, cursorY, cardRight, cursorY + detailsCardHeight)
    fillPaint.color = cardBgColor
    canvas.drawRoundRect(detailsRect, 28f, 28f, fillPaint)
    canvas.drawRoundRect(detailsRect, 28f, 28f, strokePaint)

    val dividerPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dividerColor
        strokeWidth = 2f
      }
    val ellipsizePaint = TextPaint(textPaint)
    val maxDetailRowWidth = (cardRight - 36f) - (cardLeft + 36f)
    var detailY = cursorY + detailsHeaderPad
    detailRows.forEachIndexed { index, (label, value) ->
      val textY = detailY + 26f
      textPaint.apply {
        color = textPrimary
        textSize = 25f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
      }
      val valueWidth = textPaint.measureText(value)
      canvas.drawText(value, cardRight - 36f, textY, textPaint)

      ellipsizePaint.apply {
        color = textSecondary
        textSize = 25f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
      }
      val availableLabelWidth = (maxDetailRowWidth - valueWidth - 24f).coerceAtLeast(120f)
      val safeLabel =
        TextUtils.ellipsize(label, ellipsizePaint, availableLabelWidth, TextUtils.TruncateAt.END)
          .toString()
      canvas.drawText(safeLabel, cardLeft + 36f, textY, ellipsizePaint)

      if (index < detailRows.lastIndex) {
        val lineY = detailY + detailRowHeight - 10f
        canvas.drawLine(cardLeft + 36f, lineY, cardRight - 36f, lineY, dividerPaint)
      }
      detailY += detailRowHeight
    }

    // 5. Footer Disclaimer
    textPaint.apply {
      color = textSecondary
      textSize = 22f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
      textAlign = Paint.Align.CENTER
    }
    canvas.drawText(
      context.getString(R.string.share_card_footer_note),
      IMAGE_WIDTH / 2f,
      cursorY + detailsCardHeight + 54f,
      textPaint,
    )

    return bitmap
  }

  private fun colorIntForCategory(category: BmiCategory): Int =
    when (category) {
      BmiCategory.UNDERWEIGHT -> "#0284C7".toColorInt()
      BmiCategory.NORMAL -> "#0D9488".toColorInt()
      BmiCategory.OVERWEIGHT -> "#D97706".toColorInt()
      BmiCategory.OBESITY_CLASS_1 -> "#EA580C".toColorInt()
      BmiCategory.OBESITY_CLASS_2 -> "#DC2626".toColorInt()
      BmiCategory.OBESITY_CLASS_3 -> "#991B1B".toColorInt()
    }

  private fun withAlpha(color: Int, alpha: Int): Int {
    return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
  }
}
