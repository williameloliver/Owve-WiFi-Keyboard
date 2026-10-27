package cl.owve.wifikeyboard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayDeque;

/**
 * Cursor remoto OWVE, sin leer textos ni el arbol de las otras apps.
 * Android API 26+ permite continuar un gesto real de dedo entre varios
 * dispatchGesture() usando StrokeDescription.continueStroke().
 *
 * Protocolo v1.3: M DOWN / M MOVE dx dy / M UP / M HOLD.
 * La liberacion se transmite tambien al perder el foco o cerrar en XP.
 */
public final class RemoteMouseAccessibilityService extends AccessibilityService {
    private static volatile RemoteMouseAccessibilityService current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private Pointer pointer;
    private WindowManager.LayoutParams params;
    private boolean active;
    private float x, y;
    private int width = 1000, height = 600;
    private static final int CURSOR = 42;
    private PowerManager.WakeLock mouseScreenLock;
    private SharedPreferences settings;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged =
        (prefs, key) -> {
            if ("keep_screen_on_typing".equals(key) && !prefs.getBoolean(key,true))
                main.post(this::releaseWake);
        };
    private final Runnable releaseScreen = this::releaseWake;
    private static final long IDLE_MS = 120000L;

    // Solo el hilo principal toca estos estados. Se admite una cola corta para
    // clics consecutivos (incluso cuando un DOWN/UP llega en menos de 65 ms).
    private final ArrayDeque<Drag> queuedDrags = new ArrayDeque<>();
    private Drag ongoingDrag;
    private Drag physicalButtonDrag;
    private static final long DRAG_WATCHDOG_MS = 5000L;
    private static final long DRAG_INITIAL_MS = 65L;
    private static final long DRAG_MOVING_MS = 38L;
    private static final long DRAG_STILL_MS = 130L;
    private static final long DRAG_RELEASE_MS = 38L;
    private final Runnable dragWatchdog = new Runnable() {
        @Override public void run() {
            Drag d = physicalButtonDrag;
            if (d == null) return;
            long idle = SystemClock.uptimeMillis() - d.lastInputAt;
            if (idle >= DRAG_WATCHDOG_MS) {
                // Nunca dejar un dedo virtual presionado cuando falla el Wi-Fi.
                releasePhysicalButton();
            } else {
                main.postDelayed(this, Math.min(1000L, DRAG_WATCHDOG_MS-idle));
            }
        }
    };

    private static final class Drag {
        final float startX, startY;
        float requestedX, requestedY;
        float lastX, lastY;
        long lastInputAt;
        boolean pressed = true;
        GestureDescription.StrokeDescription previousStroke;
        Drag(float x, float y) {
            startX=x; startY=y;
            requestedX=x; requestedY=y;
            lastX=x; lastY=y;
            lastInputAt=SystemClock.uptimeMillis();
        }
    }

    static void accept(String command) {
        RemoteMouseAccessibilityService s = current;
        if (s != null) s.main.post(() -> s.execute(command));
    }
    static void stopIfAvailable() {
        RemoteMouseAccessibilityService s = current;
        if (s != null) s.main.post(() -> s.setActive(false));
    }
    static boolean isAvailable() { return current != null; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windows = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        if (windows != null) {
            Point size = new Point();
            windows.getDefaultDisplay().getRealSize(size);
            width = size.x;
            height = size.y;
            x = width / 2f;
            y = height / 2f;
        }
        current = this;
        settings = getSharedPreferences("owve_keyboard", MODE_PRIVATE);
        settings.registerOnSharedPreferenceChangeListener(settingsChanged);
        SessionServer.ensureStarted(this);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { setActive(false); }
    @Override public void onDestroy() {
        setActive(false);
        if (settings != null) settings.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        if (current == this) current = null;
        super.onDestroy();
    }

    private static int safeNumber(String s) {
        try { return Math.max(-4096, Math.min(4096, Integer.parseInt(s))); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private float clamp(float n, float a, float b) { return Math.max(a,Math.min(n,b)); }

    private void execute(String command) {
        if ("START".equals(command)) { setActive(true); return; }
        if ("STOP".equals(command)) { setActive(false); return; }
        if (!active) return;
        keepScreen();
        if (command.startsWith("MOVE ")) {
            String[] parts = command.split(" ");
            if (parts.length != 3) return;
            x = clamp(x + safeNumber(parts[1]), 0, width - 1);
            y = clamp(y + safeNumber(parts[2]), 0, height - 1);
            refreshPointer();
            if (physicalButtonDrag != null) {
                physicalButtonDrag.requestedX = x;
                physicalButtonDrag.requestedY = y;
                physicalButtonDrag.lastInputAt = SystemClock.uptimeMillis();
            }
        } else if ("DOWN".equals(command)) {
            buttonDown();
        } else if ("UP".equals(command)) {
            releasePhysicalButton();
        } else if ("HOLD".equals(command)) {
            if (physicalButtonDrag != null)
                physicalButtonDrag.lastInputAt = SystemClock.uptimeMillis();
        } else if ("CLICK".equals(command)) {
            // Permite seguir usando el EXE anterior solo para clic corto.
            if (ongoingDrag == null) gesture(x,y,x,y,55);
        } else if ("RIGHT".equals(command)) {
            if (ongoingDrag == null) gesture(x,y,x,y,750);
        } else if (command.startsWith("WHEEL ")) {
            if (ongoingDrag != null) return; // No interrumpir un arrastre.
            int steps = safeNumber(command.substring(6));
            int distance = Math.min(500, Math.abs(steps) * 170);
            if (steps != 0) {
                float startY = clamp(y,80,height-80);
                float endY = clamp(startY + (steps > 0 ? -distance : distance),45,height-45);
                gesture(x,startY,x,endY,220);
            }
        }
    }

    private void buttonDown() {
        if (physicalButtonDrag != null) return;
        Drag d = new Drag(x,y);
        physicalButtonDrag = d;
        if (ongoingDrag == null) beginDrag(d);
        else if (queuedDrags.size() < 8) queuedDrags.add(d);
        else { physicalButtonDrag = null; return; }
        main.removeCallbacks(dragWatchdog);
        main.postDelayed(dragWatchdog, DRAG_WATCHDOG_MS);
    }
    private void releasePhysicalButton() {
        Drag d = physicalButtonDrag;
        physicalButtonDrag = null;
        main.removeCallbacks(dragWatchdog);
        if (d != null) d.pressed = false;
        // La continuidad final (up) se envia al terminar el segmento pendiente.
    }
    private void releaseAllDrags() {
        releasePhysicalButton();
        queuedDrags.clear();
        if (ongoingDrag != null) ongoingDrag.pressed = false;
    }

    private void beginDrag(Drag d) {
        if (!active || ongoingDrag != null) return;
        ongoingDrag = d;
        Path path = new Path();
        path.moveTo(d.startX, d.startY);
        // Aun si ya recibimos UP, comenzamos con willContinue para que el
        // siguiente segmento finalice correctamente (clic rapido real).
        GestureDescription.StrokeDescription initial =
            new GestureDescription.StrokeDescription(path,0,DRAG_INITIAL_MS,true);
        dispatchSegment(d,initial);
    }
    private void dispatchSegment(Drag d, GestureDescription.StrokeDescription stroke) {
        if (ongoingDrag != d) return;
        d.previousStroke = stroke;
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        try {
            if (!dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription ignored) {
                    segmentFinished(d,stroke,true);
                }
                @Override public void onCancelled(GestureDescription ignored) {
                    segmentFinished(d,stroke,false);
                }
            }, main)) segmentFinished(d,stroke,false);
        } catch (RuntimeException ex) {
            segmentFinished(d,stroke,false);
        }
    }
    private void segmentFinished(Drag d, GestureDescription.StrokeDescription segment,
                                 boolean completed) {
        if (ongoingDrag != d || d.previousStroke != segment) return;
        if (!completed || !segment.willContinue()) {
            ongoingDrag = null;
            d.previousStroke = null;
            if (!active) { queuedDrags.clear(); return; }
            while (!queuedDrags.isEmpty()) {
                Drag next = queuedDrags.remove();
                if (next != null) { beginDrag(next); break; }
            }
            return;
        }
        // Del ultimo punto del segmento anterior hacia el mas reciente
        // recibido del trackpad. No se acumulan decenas de gestos en cola.
        float nextX = d.requestedX, nextY = d.requestedY;
        Path path = new Path();
        path.moveTo(d.lastX,d.lastY);
        boolean moving = (d.lastX != nextX || d.lastY != nextY);
        if (moving) path.lineTo(nextX,nextY);
        boolean keepPressed = d.pressed && active;
        long duration = !keepPressed ? DRAG_RELEASE_MS :
                         (moving ? DRAG_MOVING_MS : DRAG_STILL_MS);
        GestureDescription.StrokeDescription continuation;
        try { continuation = segment.continueStroke(path,0,duration,keepPressed); }
        catch (RuntimeException ignored) {
            ongoingDrag = null;
            queuedDrags.clear();
            return;
        }
        d.lastX = nextX;
        d.lastY = nextY;
        dispatchSegment(d,continuation);
    }

    private void setActive(boolean yes) {
        if (yes == active) return;
        active = yes;
        if (!yes) {
            releaseAllDrags();
            main.removeCallbacks(releaseScreen);
            releaseWake();
        }
        if (yes) {
            try {
                if (windows == null) { active=false; return; }
                pointer = new Pointer(this);
                params = new WindowManager.LayoutParams(CURSOR,CURSOR,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
                params.gravity=Gravity.LEFT | Gravity.TOP;
                params.x=(int)x-CURSOR/2;
                params.y=(int)y-CURSOR/2;
                windows.addView(pointer,params);
            } catch (RuntimeException ex) {
                active=false; pointer=null; params=null;
            }
        } else {
            if (pointer != null && windows != null) {
                try { windows.removeView(pointer); } catch (RuntimeException ignored) { }
            }
            pointer=null; params=null;
        }
    }
    private void keepScreen() {
        if (!getSharedPreferences("owve_keyboard",MODE_PRIVATE)
                .getBoolean("keep_screen_on_typing",true)) return;
        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) return;
        try {
            if (mouseScreenLock == null) {
                mouseScreenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "owve:WiFiMouseActive");
                mouseScreenLock.setReferenceCounted(false);
            }
            if (!mouseScreenLock.isHeld()) mouseScreenLock.acquire();
            main.removeCallbacks(releaseScreen);
            main.postDelayed(releaseScreen,IDLE_MS);
        } catch (SecurityException ignored) { }
    }
    private void releaseWake() {
        if (mouseScreenLock != null && mouseScreenLock.isHeld()) {
            try { mouseScreenLock.release(); } catch (RuntimeException ignored) { }
        }
    }
    private void refreshPointer() {
        if (pointer == null || params == null) return;
        params.x=(int)x-CURSOR/2;
        params.y=(int)y-CURSOR/2;
        try { windows.updateViewLayout(pointer,params); }
        catch (RuntimeException ignored) { setActive(false); }
    }
    private void gesture(float x1,float y1,float x2,float y2,long duration) {
        if (!active) return;
        Path p = new Path();
        p.moveTo(x1,y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2,y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p,0,duration));
        dispatchGesture(b.build(),null,null);
    }
    private static final class Pointer extends View {
        private final Paint p = new Paint(3);
        Pointer(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE,null); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            p.setColor(Color.BLACK); p.setStrokeWidth(4f);
            c.drawLine(21,21,21,36,p); c.drawLine(21,21,36,21,p);
            p.setColor(Color.rgb(255,240,80)); p.setStrokeWidth(2f);
            c.drawLine(21,20,21,35,p); c.drawLine(20,21,35,21,p);
            p.setColor(Color.WHITE); c.drawCircle(21,21,4,p);
        }
    }
}
