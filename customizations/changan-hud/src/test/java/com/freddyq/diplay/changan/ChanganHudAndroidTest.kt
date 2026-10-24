package com.freddyq.diplay.changan

import android.os.Build
import com.freddyq.diplay.customization.VehicleCustomizations
import com.shilapi.xcertplay.hud.BydOutputSettings

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28,33])
class ChanganHudAndroidTest {
    @Test fun `guidance targets only interactive with typed receiver fields`() {
        val frame = ChanganHudFrame(8,33,"HUD测试路B","起点",2)
        val intent = ChanganHudPacket.Guidance(frame).toIntent()
        assertEquals("com.incall.interactive",intent.`package`)
        assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND",intent.action)
        assertEquals(10001,intent.getIntExtra("KEY_TYPE",-1))
        assertEquals(8,intent.getIntExtra("NEW_ICON",-1))
        assertEquals(33,intent.getIntExtra("SEG_REMAIN_DIS",-1))
        assertEquals("HUD测试路B",intent.getStringExtra("NEXT_ROAD_NAME"))
        assertEquals(2,intent.getIntExtra("ROUND_ABOUT_NUM",-1))
        assertEquals(2,intent.getIntExtra("ROUNG_ABOUT_NUM",-1))
        assertTrue(intent.flags and 0x01000000 != 0)
        assertNull(intent.component)
        assertFalse(intent.hasExtra("IS_BYD_MAP"))
        assertFalse(intent.hasExtra("ROUTE_ALL_DIS")) // Total route length is not supplied by these iAP2 frames.
    }

    @Test fun `clear uses only the experimentally verified state packet`() {
        val intent = ChanganHudPacket.State(9).toIntent()
        assertEquals(10019,intent.getIntExtra("KEY_TYPE",-1))
        assertEquals(9,intent.getIntExtra("EXTRA_STATE",-1))
        assertEquals(0,intent.getIntExtra("EXTRA_CROSS_MAP",-1))
        assertFalse(intent.hasExtra("NEW_ICON"))
    }

    @Test fun `setting defaults on and remains independent of BYD navigation`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay_changan_hud",0).edit().clear().commit()
        BydOutputSettings.setEnabled(app,true)
        assertTrue(ChanganHudSettings.enabled(app))
        ChanganHudSettings.setEnabled(app,false)
        assertFalse(ChanganHudSettings.enabled(app))
        assertTrue(BydOutputSettings.enabled(app))
    }

    @Test fun `custom edition always exposes the setting without model or receiver gating`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay_changan_hud",0).edit().clear().commit()
        ReflectionHelpers.setStaticField(Build::class.java,"MODEL","UNRECOGNIZED_MODEL")
        assertFalse(ChanganHudSettings.receiverInstalled(app))
        val module = ChanganCustomization()
        val setting = module.settings(app).single().toggles.single()
        assertTrue(setting.enabled)
        setting.onChanged(false)
        assertFalse(module.settings(app).single().toggles.single().enabled)
    }

    @Test fun `manifest initializer registers once and contributes its settings`() {
        val initializer = ChanganCustomizationInitializer()
        assertTrue(initializer.onCreate())
        assertTrue(initializer.onCreate())
        assertEquals(1,VehicleCustomizations.settings(RuntimeEnvironment.getApplication())
            .count { it.title == R.string.settings_changan_hud_title })
    }
}
