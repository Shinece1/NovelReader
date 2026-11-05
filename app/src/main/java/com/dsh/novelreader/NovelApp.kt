package com.dsh.novelreader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Process-wide setup. PDFBox-Android needs its font resources unpacked before
 * any text extraction happens; doing it here keeps the first import fast.
 */
class NovelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
