package cl.owve.wifikeyboard;

import android.inputmethodservice.InputMethodService;
import android.content.Context;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class RemoteKeyboardService extends InputMethodService {
    private volatile boolean stopping=false;
    private volatile boolean passwordField=false;
    private volatile Socket connectedSocket;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView indicator;

    @Override public void onCreate() {
        super.onCreate();
        stopping=false;
        serverThread=new Thread(this::acceptLoop, "OWVE-Keyboard-WiFi");
        serverThread.start();
    }

    @Override public View onCreateInputView() {
        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(12,2,8,2);
        bar.setBackgroundColor(Color.rgb(34,42,48));
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

    private void acceptLoop() {
        try (ServerSocket ss=new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(Config.PORT));
            serverSocket=ss;
            ss.setSoTimeout(1200);
            showStatus("esperando al Compaq");
            while(!stopping) {
                try {
                    Socket s=ss.accept();
                    connectedSocket=s;
                    try { runClient(s); }
                    finally { try { s.close(); } catch(IOException ignored){} connectedSocket=null; }
                    if(!stopping) showStatus("esperando al Compaq");
                } catch(SocketTimeoutException ignored) {
                    // Permite detener el servicio aunque nadie este conectado.
                } catch(IOException e) {
                    if(!stopping) showStatus("conexion interrumpida");
                }
            }
        } catch(IOException e) {
            if(!stopping) showStatus("puerto " + Config.PORT + " ocupado");
        } finally { serverSocket=null; }
    }

    private String readLineBounded(BufferedReader r, int limit) throws IOException {
        StringBuilder sb=new StringBuilder();
        int ch;
        while((ch=r.read())!=-1) {
            if(ch=='\n') return sb.toString();
            if(ch!='\r') sb.append((char)ch);
            if(sb.length()>limit) throw new IOException("linea demasiado larga");
        }
        return null;
    }

    private void runClient(Socket s) throws IOException {
        s.setSoTimeout(8000);
        s.setTcpNoDelay(true);
        BufferedReader input=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
        String auth=readLineBounded(input,100);
        if(!("AUTH "+Config.pin(this)).equals(auth)) {
            s.getOutputStream().write("NO\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        s.getOutputStream().write("OK\n".getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        s.setSoTimeout(0); // La ventana de Windows puede quedar inactiva sin desconectar.
        showStatus("Compaq conectado");
        String command;
        while(!stopping && (command=readLineBounded(input,1024))!=null) {
            if(command.startsWith("T ")) {
                String value=command.substring(2);
                if(!value.isEmpty()) ui.post(() -> insertText(value));
            } else if(command.startsWith("K ")) {
                String value=command.substring(2);
                ui.post(() -> applyKey(value));
            }
        }
    }

    private void insertText(String text) {
        if(passwordField) return;
        InputConnection ic=getCurrentInputConnection();
        if(ic!=null) ic.commitText(text,1);
    }

    private void pressKey(InputConnection ic,int keyCode) {
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,keyCode));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,keyCode));
    }

    private void applyKey(String key) {
        if(passwordField) return;
        InputConnection ic=getCurrentInputConnection();
        if(ic==null) return;
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

    @Override public void onDestroy() {
        stopping=true;
        try { if(connectedSocket!=null) connectedSocket.close(); } catch(IOException ignored){}
        try { if(serverSocket!=null) serverSocket.close(); } catch(IOException ignored){}
        super.onDestroy();
    }
}
