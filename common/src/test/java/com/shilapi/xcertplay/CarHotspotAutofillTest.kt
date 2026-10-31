package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.freddyq.diplay.customization.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CarHotspotAutofillTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val extensions get()=ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations,"extensions")
    private var old=emptyList<VehicleCustomization>()
    private var detected:PlatformHotspotCredentials?=PlatformHotspotCredentials(" Read AP ","read-key-123")
    private var reads=0
    @Before fun setup() {
        old=extensions.toList(); extensions.clear()
        app.getSharedPreferences("xcertplay_airplay",0).edit().clear().commit()
        AirPlayPersistence.saveManualHotspotSsid(app,"Manual AP")
        AirPlayPersistence.saveManualHotspotPassphrase(app,"manual-key")
        VehicleCustomizations.register(object:VehicleCustomization {
            override val id="fake-system-ap"
            override fun platformHotspotCredentials(context:Context):PlatformHotspotCredentials? { reads++; return detected }
        })
    }
    @After fun restore() { ShadowDialog.getLatestDialog()?.dismiss(); extensions.clear(); extensions.addAll(old) }
    private fun inputs(v:View):List<EditText> = when(v) {
        is EditText -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { inputs(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun dialog(existing:Boolean=false,done:(String,String)->Unit={_,_->}):List<EditText> {
        val activity=Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(com.freddyq.diplay.glass.R.style.Theme_DiPlay_Glass)
        val method=DiPlayActivity::class.java.declaredMethods.single { it.name=="askHotspotCredentials" }.apply { isAccessible=true }
        method.invoke(activity,existing,done)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return inputs(ShadowDialog.getLatestDialog()!!.window!!.decorView)
    }
    @Test fun homeDialogAutofillsAndOnlySavesThroughExistingSaveAction() {
        val fields=dialog { name,key ->
            AirPlayPersistence.saveManualHotspotSsid(app,name)
            AirPlayPersistence.saveManualHotspotPassphrase(app,key)
        }
        assertEquals(listOf(" Read AP ","read-key-123"),fields.map { it.text.toString() })
        assertEquals("Manual AP",AirPlayPersistence.loadManualHotspotSsid(app))
        (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(" Read AP ",AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals("read-key-123",AirPlayPersistence.loadManualHotspotPassphrase(app))
        assertEquals(1,reads)
    }
    @Test fun unreadableSystemApKeepsManualFields() {
        detected=null
        assertEquals(listOf("Manual AP","manual-key"),dialog().map { it.text.toString() })
    }
    @Test fun clientWifiDialogDoesNotReadOrOverwriteCarHotspot() {
        AirPlayPersistence.saveExistingWifiCredentials(app,"Client WiFi","client-key")
        assertEquals(listOf("Client WiFi","client-key"),dialog(existing=true).map { it.text.toString() })
        assertEquals(0,reads)
        assertEquals("Manual AP",AirPlayPersistence.loadManualHotspotSsid(app))
    }
    @Test fun connectedHostSettingsAutofillWithoutPersistingOnOpen() {
        val host=Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val view=ReflectionHelpers.callInstanceMethod<View>(host,"buildHotspotModeSection")
        assertTrue(inputs(view).any { it.text.toString()==" Read AP " })
        assertTrue(inputs(view).any { it.text.toString()=="read-key-123" })
        assertEquals("Manual AP",AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals(1,reads)
    }
}
