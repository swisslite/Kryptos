package com.kryptos.android.ui

import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import com.journeyapps.barcodescanner.camera.CenterCropStrategy

class PortraitCaptureActivity : CaptureActivity() {
    private var scanner: DecoratedBarcodeView? = null

    override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        scanner = view
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val barcodeView = scanner?.barcodeView ?: return
        barcodeView.cameraSettings.apply {
            isAutoFocusEnabled = true
            isContinuousFocusEnabled = true
            isMeteringEnabled = true
        }
        barcodeView.previewScalingStrategy = MaxResolutionStrategy()
        barcodeView.marginFraction = DECODE_MARGIN_FRACTION
    }

    private companion object {
        const val DECODE_MARGIN_FRACTION = 0.02
    }
}

private class MaxResolutionStrategy : CenterCropStrategy() {
    override fun getScore(size: Size?, desired: Size?): Float {
        if (size == null || size.width <= 0 || size.height <= 0) return 0f
        val pixels = size.width.toFloat() * size.height.toFloat()
        val capped = if (pixels <= TARGET_PIXELS) pixels else TARGET_PIXELS * (TARGET_PIXELS / pixels)
        if (desired == null || desired.width <= 0 || desired.height <= 0) return capped / TARGET_PIXELS
        val aspect = size.width.toFloat() / size.height.toFloat()
        val target = desired.width.toFloat() / desired.height.toFloat()
        val match = if (aspect > target) target / aspect else aspect / target
        return capped * match * match / TARGET_PIXELS
    }

    private companion object {
        const val TARGET_PIXELS = 1920f * 1080f
    }
}
