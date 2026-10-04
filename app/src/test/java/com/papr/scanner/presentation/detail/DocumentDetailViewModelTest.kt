package com.papr.scanner.presentation.detail

import com.papr.scanner.usage.MeteredFeature
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentDetailViewModelTest {

    @Test
    fun `a plain export uses no limited features`() {
        assertEquals(emptySet<MeteredFeature>(), meteredFeaturesFor(ExportSettings()))
    }

    @Test
    fun `an export with every option uses one of each`() {
        val settings = ExportSettings(
            ocrEnabled = true,
            compressToTargetSize = true,
            passwordEnabled = true,
            password = "secret",
        )
        assertEquals(
            setOf(MeteredFeature.OCR_EXPORT, MeteredFeature.COMPRESSED_EXPORT, MeteredFeature.PASSWORD_EXPORT),
            meteredFeaturesFor(settings),
        )
    }

    @Test
    fun `a password switched on but left blank isn't counted`() {
        val settings = ExportSettings(passwordEnabled = true, password = "  ")
        assertEquals(emptySet<MeteredFeature>(), meteredFeaturesFor(settings))
    }

    @Test
    fun `a typed password with the switch off isn't counted`() {
        val settings = ExportSettings(passwordEnabled = false, password = "secret")
        assertEquals(emptySet<MeteredFeature>(), meteredFeaturesFor(settings))
    }

    @Test
    fun `an image export uses no limited features, even with PDF options left on`() {
        val settings = ExportSettings(
            format = ExportFormat.IMAGES,
            ocrEnabled = true,
            compressToTargetSize = true,
            passwordEnabled = true,
            password = "secret",
        )
        assertEquals(emptySet<MeteredFeature>(), meteredFeaturesFor(settings))
    }
}
