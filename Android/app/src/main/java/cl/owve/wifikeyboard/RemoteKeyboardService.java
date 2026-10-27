package cl.owve.wifikeyboard;

import android.inputmethodservice.InputMethodService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;


public class RemoteKeyboardService extends InputMethodService {
    private volatile boolean passwordField=false;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView indicator;
    private View inputBar;

    // La escritura por InputConnection no actualiza el temporizador de pantalla
    // del sistema. Este bloqueo temporal solo se activa al recibir una tecla
    // valida para un campo de texto y se libera tras la inactividad.
    private static final long KEYBOARD_IDLE_MS = 120000L;
    private PowerManager.WakeLock typingScreenLock;
    private SharedPreferences settings;
    private final Runnable releaseScreenAfterIdle = this::releaseScreenLock;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged =
            (preferences, key) -> {
                if ("keep_screen_on_typing".equals(key)
                        && !preferences.getBoolean(key, true)) {
                    ui.post(this::stopScreenTimer);
                }
            };

    private void keepScreenOnWhileTyping() {
        // Solo se llama desde el hilo UI, despues de verificar InputConnection.
        if (settings == null || !settings.getBoolean("keep_screen_on_typing", true)) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        // No encender una pantalla que el usuario haya apagado manualmente.
        if (pm == null || !pm.isInteractive()) return;
        // Si la barra del IME esta visible, utilizar tambien el metodo
        // recomendado para ventanas, sin permisos adicionales.
        if (inputBar != null) inputBar.setKeepScreenOn(true);
        try {
            if (typingScreenLock == null) {
                typingScreenLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "owve:WiFiKeyboardTyping");
                typingScreenLock.setReferenceCounted(false);
            }
            if (!typingScreenLock.isHeld()) typingScreenLock.acquire();
            ui.removeCallbacks(releaseScreenAfterIdle);
            ui.postDelayed(releaseScreenAfterIdle, KEYBOARD_IDLE_MS);
        } catch (SecurityException ignored) {
            // El teclado sigue escribiendo, aunque el fabricante bloquee wake locks.
        }
    }

    private void releaseScreenLock() {
        if (inputBar != null) inputBar.setKeepScreenOn(false);
        if (typingScreenLock != null && typingScreenLock.isHeld()) {
            try { typingScreenLock.release(); } catch (RuntimeException ignored) {}
        }
    }

    private void stopScreenTimer() {
        ui.removeCallbacks(releaseScreenAfterIdle);
        releaseScreenLock();
    }

    @Override public void onCreate() {
        super.onCreate();
        settings = getSharedPreferences("owve_keyboard", MODE_PRIVATE);
        settings.registerOnSharedPreferenceChangeListener(settingsChanged);
        activeKeyboard = this;
        SessionServer.ensureStarted(this);
    }

    @Override public View onCreateInputView() {
        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(12,2,8,2);
        bar.setBackgroundColor(Color.rgb(34,42,48));
        inputBar=bar;
        indicator=new TextView(this);
        indicator.setTextColor(Color.WHITE);
        indicator.setTextSize(13);
        indicator.setText("OWVE WiFi | listo");
        bar.addView(indicator,new LinearLayout.LayoutParams(0,dp(42),1));
        Button choose=new Button(this);
        choose.setText("Cambiar");
        choose.setAllCaps(false);
        choose.setTextSize(11);
        choose.setOnClickListener(v -> {
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null) imm.showInputMethodPicker();
        });
        bar.addView(choose,new LinearLayout.LayoutParams(dp(100),dp(42)));
        return bar;
    }

    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private void showStatus(String status) {
        ui.post(() -> { if(indicator!=null) indicator.setText("OWVE WiFi | " + status); });
    }

    @Override public void onStartInput(EditorInfo info,boolean restarting) {
        super.onStartInput(info,restarting);
        if(info==null) { passwordField=false; return; }
        int klass=info.inputType & InputType.TYPE_MASK_CLASS;
        int kind=info.inputType & InputType.TYPE_MASK_VARIATION;
        passwordField=(klass==InputType.TYPE_CLASS_TEXT &&
                  (kind==InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                   kind==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                   kind==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                   (klass==InputType.TYPE_CLASS_NUMBER && kind==InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    static volatile RemoteKeyboardService activeKeyboard;
    static void deliver(String message) {
        RemoteKeyboardService im = activeKeyboard;
        if (im == null) return;
        if (message.startsWith("T "))
            im.ui.post(() -> im.insertText(message.substring(2)));
        else if (message.startsWith("K "))
            im.ui.post(() -> im.applyKey(message.substring(2)));
    }
    static void mouseActivity() {
        RemoteKeyboardService im = activeKeyboard;
        if (im != null) im.ui.post(im::keepScreenOnWhileTyping);
    }
    static void connected(boolean yes) {
        RemoteKeyboardService im = activeKeyboard;
        if (im != null) im.showStatus(yes ? "Compaq conectado" : "esperando al Compaq");
    }

    private void insertText(String text) {
        if(passwordField) return;
        InputConnection ic=getCurrentInputConnection();
        if(ic!=null) {
            keepScreenOnWhileTyping();
            ic.commitText(text,1);
        }
    }

    private void pressKey(InputConnection ic,int keyCode) {
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,keyCode));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,keyCode));
    }

    private void applyKey(String key) {
        if(passwordField) return;
        InputConnection ic=getCurrentInputConnection();
        if(ic==null) return;
        keepScreenOnWhileTyping();
        switch(key) {
            case "BS":
                CharSequence selected=ic.getSelectedText(0);
                if(selected!=null && selected.length()>0) ic.commitText("",1);
                else ic.deleteSurroundingTextInCodePoints(1,0);
                break;
            case "DEL":
                CharSequence selectedNext=ic.getSelectedText(0);
                if(selectedNext!=null && selectedNext.length()>0) ic.commitText("",1);
                else ic.deleteSurroundingTextInCodePoints(0,1);
                break;
            case "ENTER": pressKey(ic,KeyEvent.KEYCODE_ENTER); break;
            case "TAB": pressKey(ic,KeyEvent.KEYCODE_TAB); break;
            case "LEFT": pressKey(ic,KeyEvent.KEYCODE_DPAD_LEFT); break;
            case "RIGHT": pressKey(ic,KeyEvent.KEYCODE_DPAD_RIGHT); break;
            case "UP": pressKey(ic,KeyEvent.KEYCODE_DPAD_UP); break;
            case "DOWN": pressKey(ic,KeyEvent.KEYCODE_DPAD_DOWN); break;
            case "HOME": pressKey(ic,KeyEvent.KEYCODE_MOVE_HOME); break;
            case "END": pressKey(ic,KeyEvent.KEYCODE_MOVE_END); break;
            case "PAGEUP": pressKey(ic,KeyEvent.KEYCODE_PAGE_UP); break;
            case "PAGEDOWN": pressKey(ic,KeyEvent.KEYCODE_PAGE_DOWN); break;
            case "SELECTALL": ic.performContextMenuAction(android.R.id.selectAll); break;
            case "COPY": ic.performContextMenuAction(android.R.id.copy); break;
            case "PASTE": ic.performContextMenuAction(android.R.id.paste); break;
            case "CUT": ic.performContextMenuAction(android.R.id.cut); break;
            case "UNDO":
                ic.sendKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_Z,0,KeyEvent.META_CTRL_ON));
                ic.sendKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_Z,0,KeyEvent.META_CTRL_ON));
                break;
            default: break;
        }
    }

    @Override public void onFinishInput() {
        stopScreenTimer();
        super.onFinishInput();
    }

    @Override public void onDestroy() {
        if(activeKeyboard == this) activeKeyboard = null;
        stopScreenTimer();
        // El mouse puede continuar en otras apps, incluso sin editor activo.
        if (settings != null) {
            settings.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        }
        super.onDestroy();
    }
}
