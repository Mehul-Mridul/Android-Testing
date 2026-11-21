package com.example.touchfixture;
import android.app.*;
import android.os.Bundle;
import android.content.Intent;
import android.widget.*;

/** Local-only test target: no network, permissions, account data or media. */
public class FixtureActivity extends Activity {
    LinearLayout layout;
    TextView status;
    void count(String key) {
        android.content.SharedPreferences prefs=getSharedPreferences("counts",0);
        prefs.edit().putInt(key,prefs.getInt(key,0)+1).commit();
        update();
    }
    void update() {
        android.content.SharedPreferences p=getSharedPreferences("counts",0);
        status.setText("Main="+p.getInt("main",0)+" Dialog="+p.getInt("dialog",0));
    }
    void button(String name, Runnable action) {
        Button b=new Button(this);b.setText(name);b.setTextSize(20);
        layout.addView(b,new LinearLayout.LayoutParams(-1,180));
        b.setOnClickListener(v->action.run());
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(30,240,30,30);
        status=new TextView(this);status.setTextSize(24);layout.addView(status);
        button("Increment main",()->count("main"));
        button("Show save popup",()->new AlertDialog.Builder(this).setTitle("Local save confirmation")
            .setMessage("This dialog must not stop touch recording.")
            .setPositiveButton("Save locally",(d,w)->count("dialog")).setNegativeButton("Cancel",null).show());
        button("Reset counters",()->{getSharedPreferences("counts",0).edit().clear().commit();update();});
        setContentView(layout);update();
    }
}
