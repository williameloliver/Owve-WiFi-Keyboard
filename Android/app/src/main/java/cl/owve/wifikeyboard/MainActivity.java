package cl.owve.wifikeyboard;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.Context;
import android.provider.Settings;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView details;

    private TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setPadding(0, 10, 0, 10);
        return v;
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 24);
        root.addView(text("OWVE WiFi Keyboard", 23));
        root.addView(text("Convierte tu PC Windows XP en teclado de esta tablet. Sin espejo de pantalla ni Accesibilidad.", 15));
        details = text("", 19);
        root.addView(details);
        Button enable = new Button(this);
        enable.setText("1. Habilitar teclado OWVE");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable);
        Button choose = new Button(this);
        choose.setText("2. Seleccionar teclado OWVE");
        choose.setOnClickListener(v -> {
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null) imm.showInputMethodPicker();
        });
        root.addView(choose);
        Button refresh = new Button(this);
        refresh.setText("Actualizar direccion IP");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);
        root.addView(text("3. Abre un campo de texto en la tablet.\n4. En Windows XP, escribe la IP y el PIN indicados arriba y presiona Conectar.\n5. Solo se escribe en la tablet mientras la ventana de Windows tenga el foco.", 15));
        root.addView(text("Privacidad: no guarda historial ni envia a Windows el texto existente en la tablet. La conexion usa Wi-Fi local sin cifrar; evita redes publicas y contrasenas.", 13));
        setContentView(root);
        refresh();
    }
    @Override protected void onResume() { super.onResume(); if(details!=null) refresh(); }
    private void refresh() {
        details.setText("IP de tablet: " + Config.localAddress() +
                "\nPuerto: " + Config.PORT +
                "\nPIN de acceso: " + Config.pin(this));
    }
}
