package com.example.touchfixture;
import android.app.*;
import android.os.Bundle;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import android.view.WindowManager;
import android.text.*;
import android.text.InputType;
import android.widget.*;

/** Isolated keyboard fixture: no network/media/accounts; stores counters, never entered text. */
public class KeyboardFixtureActivity extends Activity {
    LinearLayout layout; TextView status; EditText field; EditText password;
    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    void stat(String key, int value) { getSharedPreferences("counts",0).edit().putInt(key,value).commit(); update(); }
    void count(String key) { stat(key,getSharedPreferences("counts",0).getInt(key,0)+1); }
    void update() { if(status!=null) status.setText("Counter="+getSharedPreferences("counts",0).getInt("main",0)+" Done="+getSharedPreferences("counts",0).getInt("done",0)); }
    void button(String text, Runnable action) { Button b=new Button(this);b.setText(text);layout.addView(b,new LinearLayout.LayoutParams(-1,dp(54)));b.setOnClickListener(v->action.run()); }
    void keyboard() { field.requestFocus(); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ScrollView scroll=new ScrollView(this);layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(dp(12),dp(56),dp(12),dp(12));scroll.addView(layout);
        status=new TextView(this);status.setTextSize(18);layout.addView(status);
        button("Increment counter",()->count("main"));
        button("Open keyboard",()->keyboard());
        field=new EditText(this);field.setHint("Local test input");field.setSingleLine(true);field.setSaveEnabled(false);field.setInputType(InputType.TYPE_CLASS_TEXT);layout.addView(field,new LinearLayout.LayoutParams(-1,dp(54)));
        field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){stat("typed_count",s.length());}public void afterTextChanged(Editable e){}});
        password=new EditText(this);password.setHint("Password exclusion test");password.setSingleLine(true);password.setSaveEnabled(false);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);layout.addView(password,new LinearLayout.LayoutParams(-1,dp(54)));
        button("Done editing",()->{ ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);field.clearFocus();password.clearFocus();count("done"); });
        button("Open input dialog",()->{ EditText edit=new EditText(this);edit.setSingleLine(true);edit.setSaveEnabled(false);AlertDialog d=new AlertDialog.Builder(this).setTitle("Keyboard test dialog").setView(edit).setPositiveButton("Done",(x,y)->count("dialog")).create();d.setOnShowListener(x->{edit.requestFocus();d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);});d.show(); });
        setContentView(scroll);update();layout.setFocusableInTouchMode(true);layout.requestFocus();
        if(getIntent().getBooleanExtra("openKeyboard",false))field.postDelayed(()->keyboard(),600);
    }
}
