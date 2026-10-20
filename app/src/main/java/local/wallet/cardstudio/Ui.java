package local.wallet.cardstudio;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class Ui {
    static final int BG = 0xfff5f6f8, INK = 0xff182923, MUTED = 0xff738079, GREEN = 0xff287965;
    static int dp(Context c, float n) { return Math.round(c.getResources().getDisplayMetrics().density*n); }
    static GradientDrawable bg(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d;
    }
    static TextView text(Context c, String s, int size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(false); return t;
    }
    static Button button(Context c, String s, boolean primary) {
        Button b = new Button(c); b.setText(s); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(primary ? 0xffffffff : INK); b.setBackground(bg(c,primary ? INK : 0xffedf1ee,14));
        b.setPadding(dp(c,12),0,dp(c,12),0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null); return b;
    }
    static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static void gap(LinearLayout parent, int n) { View v = new View(parent.getContext()); parent.addView(v,new LinearLayout.LayoutParams(1,dp(parent.getContext(),n))); }
    static void insets(Activity a, View root, int color, boolean light) {
        a.getWindow().setStatusBarColor(color); a.getWindow().setNavigationBarColor(color);
        a.getWindow().getDecorView().setSystemUiVisibility(light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        root.setOnApplyWindowInsetsListener((v,w) -> {
            android.graphics.Insets i = w.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left,i.top,i.right,i.bottom); return w;
        }); root.requestApplyInsets();
    }
}
