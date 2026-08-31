package science.transductive.nudge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.TextView;

public final class PremiumUi {
    private PremiumUi(){}

    public static int dp(View v,int dp){return Math.round(dp*v.getResources().getDisplayMetrics().density);}
    public static int dp(Activity a,int dp){return Math.round(dp*a.getResources().getDisplayMetrics().density);}

    public static void window(Activity a,boolean urgent){
        int bg=Color.rgb(urgent?20:16,urgent?10:18,urgent?18:24);
        a.getWindow().setStatusBarColor(bg);
        a.getWindow().setNavigationBarColor(bg);
        a.getWindow().getDecorView().setBackgroundColor(bg);
    }

    public static void heading(TextView t){
        t.setTextColor(Color.WHITE);
        t.setLetterSpacing(0.01f);
        t.setShadowLayer(20f,0f,4f,0x66000000);
    }

    public static void body(TextView t){t.setTextColor(0xffe7e8ee);}

    public static void button(Button b,boolean primary){
        GradientDrawable g=new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(b,18));
        g.setColor(primary?0xfff2f4ff:0xff242733);
        g.setStroke(dp(b,1),primary?0x22ffffff:0x44ffffff);
        b.setBackground(g);
        b.setTextColor(primary?0xff101117:0xfff4f5f8);
        b.setElevation(dp(b,primary?10:5));
        b.setPadding(dp(b,18),dp(b,12),dp(b,18),dp(b,12));
        b.setStateListAnimator(null);
        b.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(.975f).scaleY(.975f).setDuration(80).start();break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(new OvershootInterpolator(.8f)).start();break;
            }
            return false;
        });
    }

    public static void reveal(View v,int order){
        v.setAlpha(0f);v.setTranslationY(dp(v,24));v.setScaleX(.96f);v.setScaleY(.96f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(Math.min(420,order*55L)).setDuration(360)
            .setInterpolator(new DecelerateInterpolator(1.7f)).start();
    }

    public static void add(ViewGroup parent,View child,ViewGroup.LayoutParams params,int order){
        parent.addView(child,params);reveal(child,order);
    }
}
