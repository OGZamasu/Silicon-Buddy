package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Pairing a phone whose camera is refused (#17), and a pairing link that is read only once,
 * on the minified release build.
 *
 * The fallback used to send a phone to the Mac's control.json token, which the Mac takes
 * only on its own loopback — so over the tailnet it could never work. Now the refusal lands
 * on a form for the code the Mac shows, and that code is spent at {@code POST /buddy/pair}
 * exactly as a scanned one is. The token form is still there, named for what it is.
 *
 * Java, like {@link FakeMac}, for the reason given there.
 */
@RunWith(AndroidJUnit4.class)
public class ManualPairingTest {

    private static final long WAIT = 20_000;
    private static final String PACKAGE = "dev.siliconoptimizer.buddy";

    private Instrumentation instrumentation;
    private UiDevice device;
    private Context context;
    private FakeMac mac;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        context = instrumentation.getTargetContext();
        // A camera nobody has allowed. Revoking stops the app if it is running, so this
        // comes before anything opens it.
        instrumentation.getUiAutomation().revokeRuntimePermission(PACKAGE, "android.permission.CAMERA");
        mac = new FakeMac();
        device.wakeUp();
        device.pressHome();
    }

    @After
    public void tearDown() throws Exception {
        mac.close();
        device.pressHome();
    }

    @Test
    public void aRefusedCameraLandsOnTheCodeFormAndTheCodeIsSpentAtBuddyPair() {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        assertNotNull(launch);
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));

        // Settings is the gear on a phone and a rail entry on a tablet; either way it has
        // the button that opens pairing, paired or not.
        UiObject2 settings = device.wait(Until.findObject(By.desc("Settings")), WAIT);
        if (settings == null) settings = device.wait(Until.findObject(By.text("Settings")), WAIT);
        assertNotNull("no way to Settings", settings);
        settings.click();
        UiObject2 open = device.wait(Until.findObject(By.text(Pattern.compile("Pair with (a|another) Mac"))), WAIT);
        assertNotNull("Settings has no pairing button", open);
        open.click();

        // The sheet opens on Scan, which asks for the camera. Once it has been refused
        // twice Android stops asking and answers "denied" at once, so the dialog may or may
        // not be there.
        UiObject2 deny = device.wait(Until.findObject(
            By.res("com.android.permissioncontroller", "permission_deny_button")), 5_000);
        if (deny != null) deny.click();

        assertNotNull("a refused camera says so, and where to go instead",
            device.wait(Until.findObject(By.text("Camera denied. Type the code your Mac shows instead.")), WAIT));
        assertNotNull("and it goes there", device.wait(Until.findObject(By.text("Pairing code")), WAIT));

        // The control token has its own tab, and says who it is for.
        UiObject2 developer = device.findObject(By.text("Developer"));
        assertNotNull(developer);
        developer.click();
        assertNotNull("the token is labelled for the emulator and development",
            device.wait(Until.findObject(By.text("Local emulator and development only")), WAIT));
        UiObject2 back = device.findObject(By.text("Enter code"));
        assertNotNull(back);
        back.click();
        assertNotNull(device.wait(Until.findObject(By.text("Pairing code")), WAIT));

        // Code, address, port — in the order the form shows them.
        List<UiObject2> fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT);
        assertNotNull(fields);
        assertTrue("expected the code, the address and the port, got " + fields.size(), fields.size() >= 3);
        fields.get(0).setText("123 456");
        fields.get(1).setText("127.0.0.1");
        fields.get(2).setText(String.valueOf(mac.port()));
        hideKeyboard();

        UiObject2 pair = device.wait(Until.findObject(By.text(Pattern.compile("Pair|Replace this Mac…"))), WAIT);
        assertNotNull("no Pair button", pair);
        boolean replacing = pair.getText().startsWith("Replace");
        pair.click();
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }

        assertTrue("the typed code never reached /buddy/pair", waitFor(() -> mac.saw("POST", "/buddy/pair")));
        String body = mac.bodyOf("POST", "/buddy/pair");
        assertTrue(body, body.contains("\"code\":\"123456\""));
        assertTrue("the sheet stays up after pairing",
            device.wait(Until.gone(By.text("Pairing code")), WAIT));
        assertNotNull("Settings names the Mac the code paired with",
            device.wait(Until.findObject(By.text("Test Mac")), WAIT));
    }

    /**
     * A link is read once. The critic's case: pair through a link, then change the display
     * density (or the font size) — which recreates the activity with the intent it was
     * opened with — and "Pair with this Mac?" came back with the code already spent.
     */
    @Test
    public void aSpentLinkIsNotOfferedAgainWhenTheActivityIsRecreated() throws Exception {
        Intent link = new Intent(Intent.ACTION_VIEW,
            Uri.parse("siliconbuddy://pair?host=127.0.0.1&port=" + mac.port() + "&code=654321"))
            .setPackage(PACKAGE)
            // A fresh task, so the link is the intent the activity is created with.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(link);
        UiObject2 confirm = device.wait(Until.findObject(By.text(Pattern.compile("Pair|Replace this Mac…"))), WAIT);
        assertNotNull("the pairing confirmation never appeared", confirm);
        boolean replacing = confirm.getText().startsWith("Replace");
        confirm.click();
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }
        assertTrue("the link never reached /buddy/pair", waitFor(() -> mac.saw("POST", "/buddy/pair")));
        assertTrue("the confirmation stays up after pairing",
            device.wait(Until.gone(By.text("Pair with this Mac?")), WAIT));

        String before = device.executeShellCommand("wm density");
        java.util.regex.Matcher physical = Pattern.compile("Physical density: (\\d+)").matcher(before);
        java.util.regex.Matcher override = Pattern.compile("Override density: (\\d+)").matcher(before);
        assertTrue(before, physical.find());
        int current = override.find() ? Integer.parseInt(override.group(1)) : Integer.parseInt(physical.group(1));
        try {
            device.executeShellCommand("wm density " + (current + 60));
            device.waitForIdle();
            assertNull("a recreated activity offered the spent code again",
                device.wait(Until.findObject(By.text("Pair with this Mac?")), 5_000));
            assertNull(device.findObject(By.text("Code 654 321")));
        } finally {
            device.executeShellCommand(before.contains("Override density:")
                ? "wm density " + current : "wm density reset");
        }
    }

    /** The keyboard can sit over the button; Back closes it, and only when it is up. */
    private void hideKeyboard() {
        try {
            String state = device.executeShellCommand("dumpsys input_method");
            if (state.contains("mInputShown=true")) device.pressBack();
        } catch (java.io.IOException ignored) {
            // Nothing to learn from; the tap below says whether it mattered.
        }
        device.waitForIdle();
    }

    private boolean waitFor(java.util.function.BooleanSupplier check) {
        long deadline = System.currentTimeMillis() + WAIT;
        while (System.currentTimeMillis() < deadline) {
            if (check.getAsBoolean()) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return check.getAsBoolean();
    }
}
