package cl.owve.wifikeyboard;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.Context;
import android.provider.Settings;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

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
        root.addView(text("OWVE WiFi Keyboard + Mouse 1.3", 23));
        root.addView(text("Controla el teclado y el mouse de tu tablet desde Windows XP, sin duplicar pantalla.", 15));
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
        Button enableMouse = new Button(this);
        enableMouse.setText("3. Permitir mouse remoto (Accesibilidad)");
        enableMouse.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(enableMouse);
        root.addView(text("En Accesibilidad, busca OWVE Mouse remoto, habilitalo y acepta el permiso. " +
                "Se necesita solo para mover un cursor y realizar toques en otras aplicaciones. " +
                "OWVE no lee contenidos de pantalla.", 13));
        Button refresh = new Button(this);
        refresh.setText("Actualizar direccion IP");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);
        CheckBox awake = new CheckBox(this);
        awake.setText("Mantener pantalla activa con teclado y mouse Wi-Fi");
        awake.setTextSize(15);
        awake.setChecked(getSharedPreferences("owve_keyboard", MODE_PRIVATE)
                .getBoolean("keep_screen_on_typing", true));
        awake.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences("owve_keyboard", MODE_PRIVATE).edit()
                        .putBoolean("keep_screen_on_typing", checked).apply());
        root.addView(awake);
        root.addView(text("La pantalla se mantiene activa al recibir teclas o movimientos del mouse. " +
                "Se libera tras 2 minutos de inactividad, o al desconectarse. " +
                "Puedes desactivarlo para ahorrar bateria.", 13));
        root.addView(text("4. En Windows XP introduce IP y PIN.\n5. Teclado: ventana seleccionada. " +
                "Mouse: doble clic en el cuadro de Windows para capturarlo; Win+X para liberarlo. " +
                "El puntero solo aparece mientras esta capturado. Mantener el clic izquierdo + mover = arrastrar; al soltar termina el gesto.", 15));
        root.addView(text("Privacidad: no guarda historial ni envia a Windows el texto existente en la tablet. La conexion usa Wi-Fi local sin cifrar; evita redes publicas y contrasenas.", 13));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }
    @Override protected void onResume() { super.onResume(); if(details!=null) refresh(); }
    private void refresh() {
        details.setText("IP de tablet: " + Config.localAddress() +
                "\nPuerto: " + Config.PORT +
                "\nPIN de acceso: " + Config.pin(this));
    }
}
