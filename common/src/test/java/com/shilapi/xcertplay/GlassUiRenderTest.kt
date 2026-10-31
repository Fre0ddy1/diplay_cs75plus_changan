package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.freddyq.diplay.glass.GlassUi
import com.freddyq.diplay.lifecycle.AppLifecycleCustomization
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Visual QA uses the actual Activity builders, no mock HTML and no car connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1280dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassUiRenderTest {
    private val extensions get() = ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations, "extensions")
    private var previous = emptyList<VehicleCustomization>()
    @Before fun prepare() {
        previous = extensions.toList(); extensions.clear()
        VehicleCustomizations.register(AppLifecycleCustomization())
        VehicleCustomizations.register(com.freddyq.diplay.changan.ChanganCustomization())
        VehicleCustomizations.register(com.freddyq.diplay.changan.audio.ChanganAudioCustomization())
        VehicleCustomizations.register(com.freddyq.diplay.changan.projection.ChanganProjectionCustomization())
        VehicleCustomizations.register(com.freddyq.diplay.hotspot.HotspotStartupCustomization())
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getPackageInfo(app.packageName, 0).apply { versionName = "0.2.22-ca75plus.23" }
        shadowOf(app.packageManager).installPackage(info)
    }
    @After fun restore() { extensions.clear(); extensions.addAll(previous) }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun capture(view: View, name: String, width: Int = 1280, height: Int = 720) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File(System.getProperty("diplay.ui.previews", "build/reports/glass-ui"))
        output.mkdirs()
        File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    @Test fun actualHomeAndSettingsRenderAtHeadUnitSizes() {
        for (width in listOf(1280, 1920)) {
        RuntimeEnvironment.setQualifiers("zh-rCN-w${width}dp-h720dp-mdpi")
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(com.freddyq.diplay.glass.R.style.Theme_DiPlay_Glass)
        fun render(page: String, group: Int = DiPlayActivity.GROUP_FREQUENT) {
            ReflectionHelpers.setField(activity, "page", page)
            ReflectionHelpers.setField(activity, "settingsGroup", group)
            ReflectionHelpers.callInstanceMethod<Void>(activity, "render")
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            assertFalse(descendants(root).filterIsInstance<TextView>().any { it.text.contains("渲染失败") })
            capture(root, "$page-$group-$width", width = width)
        }
        render("home")
        render("settings")
        render("settings", DiPlayActivity.GROUP_CONNECT)
        render("settings", DiPlayActivity.GROUP_CLUSTER)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val menu = ReflectionHelpers.callInstanceMethod<View>(host, "buildSettingsMenu")
        capture(menu, "carplay-settings-$width", width = width)
        }
    }
    @Test fun primaryAndSecondaryTextRemainLegibleOnLightSurfaces() {
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(GlassUi.text, GlassUi.background) >= 7)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(GlassUi.secondary, GlassUi.background) >= 4.5)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(GlassUi.accent, GlassUi.background) >= 4.5)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(GlassUi.muted, GlassUi.background) >= 4.5)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE, GlassUi.accent) >= 4.5)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(GlassUi.buttonBorder, GlassUi.buttonSurface) >= 3)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(GlassUi.buttonBorder, GlassUi.buttonSurfaceEnd) >= 3)
    }
    @Test fun panelsAndButtonsRestoreRoundedOutlines() {
        val app=RuntimeEnvironment.getApplication()
        val panel=GlassUi.panel(app).apply { setBounds(0,0,400,120) }
        val outline=android.graphics.Outline()
        panel.getOutline(outline)
        assertEquals(28f * app.resources.displayMetrics.density,outline.radius,0f)
        val button=GlassUi.button(app) as android.graphics.drawable.RippleDrawable
        button.setBounds(0,0,400,120)
        button.getDrawable(0).getOutline(outline)
        assertEquals(30f * app.resources.displayMetrics.density,outline.radius,0f)
    }
    @Test fun equalSizePagesShareBoundedWallpaperAndReplacementKeepsOldPageDrawable() {
        val first = GlassUi.backdrop().apply { setBounds(0, 0, 1280, 720) }
        val second = GlassUi.backdrop().apply { setBounds(0, 0, 1280, 720) }
        val pixels = ReflectionHelpers.getField<Bitmap>(first, "bitmap")
        assertSame(pixels, ReflectionHelpers.getField<Bitmap>(second, "bitmap"))
        GlassUi.backdrop().apply { setBounds(0, 0, 4096, 2160) }.let {
            val replacement = ReflectionHelpers.getField<Bitmap>(it, "bitmap")
            assertTrue(replacement.allocationByteCount <= 640 * 360 * 4)
        }
        assertFalse(pixels.isRecycled)
        val output = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        first.draw(Canvas(output))
        assertEquals(255, android.graphics.Color.alpha(output.getPixel(640, 360)))
        output.recycle()
    }
}
