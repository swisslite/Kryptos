package com.kryptos.android.ui

import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class PortraitCaptureActivity : CaptureActivity() {
    private var scanner: DecoratedBarcodeView? = null

    override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        scanner = view
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner?.barcodeView?.cameraSettings?.isContinuousFocusEnabled = true
    }
}
