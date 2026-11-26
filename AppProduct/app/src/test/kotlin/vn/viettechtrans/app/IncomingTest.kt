package vn.viettechtrans.app

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** What other apps and launcher shortcuts hand to the app (Robolectric: real Intent objects). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class IncomingTest {
    @Test
    fun selectedTextFromAnotherApp() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "Server is not responding")
        assertEquals(Incoming.Text("Server is not responding"), Incoming.from(intent))
    }

    @Test
    fun sharedTextAndSharedPicture() {
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Mật khẩu không đúng")
        assertEquals(Incoming.Text("Mật khẩu không đúng"), Incoming.from(text))
        val uri = Uri.parse("content://media/external/images/media/42")
        val image = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(Incoming.Image(uri), Incoming.from(image))
    }

    @Test
    fun launcherShortcuts() {
        assertEquals(Incoming.PickImage, Incoming.from(Intent(Incoming.ACTION_IMAGE_TRANSLATE)))
        assertEquals(Incoming.OpenTranslate, Incoming.from(Intent(Incoming.ACTION_QUICK_TRANSLATE)))
    }

    @Test
    fun nothingUsable() {
        assertNull(Incoming.from(null))
        assertNull(Incoming.from(Intent(Intent.ACTION_MAIN)))
        assertNull(Incoming.from(Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "   ")))
        assertNull(Incoming.from(Intent(Intent.ACTION_SEND).setType("image/png")))
    }
}
