package tv.cleanyour.homeinterceptor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;

// Stand-in for the new home screen while the real launcher is not installed.
public class ProbeActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("Home interceptado.\n\nEsta pantalla reemplaza el home de Amazon.\nPulsa Atrás para ver el home de Amazon (10 s sin redirigir).");
        t.setTextSize(32);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(Color.rgb(20, 40, 90));
        t.setGravity(Gravity.CENTER);
        setContentView(t);
    }

    @Override public void onBackPressed() {
        HomeService.pausedUntil = SystemClock.uptimeMillis() + 10_000;
        finish();
    }
}
