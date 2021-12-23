package com.peart.kaleidoscopeapp;

import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import top.defaults.colorpicker.ColorPickerPopup;

public class MainActivity extends AppCompatActivity {

    // the maximum rate of posting to the api
    private static final long PUT_RATE = 250; //every n ms
    private long timeOfLastPut = 0;

    // TODO: replace this logic with logic that prevents updating any UI control that is currently being interacted with.
    // It should also prevent sending changes via makePostEx as a result of the fetchSettings call.
    boolean canFetchSettings = true;
    private boolean settingUp = true;

    // track the power on/off state and clock color here as they don't have persistent state in the UI controls
    boolean powerOn = true;
    int clockColor = 0;

    SeekBar speedSeekBar, brightnessSeekBar;
    FloatingActionButton powerButton;
    Spinner clockFacesSpinner, modeNamesSpinner, drawStylesSpinner;
    List<String> clockFacesList, modeNamesList, drawStylesList;
    ArrayAdapter<String> clockAdapter, modeAdapter, drawStylesAdapter;
    ImageButton colorWheelButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        speedSeekBar = findViewById(R.id.speed_seekbar);
        brightnessSeekBar = findViewById(R.id.brightness_seekbar);
        powerButton = findViewById(R.id.powerButton);
        clockFacesSpinner = findViewById(R.id.clockFacesSpinner);
        modeNamesSpinner = findViewById(R.id.modeNamesSpinner);
        drawStylesSpinner = findViewById(R.id.drawStylesSpinner);
        colorWheelButton = findViewById(R.id.colorWheelButton);

        clockFacesList = new ArrayList<>();
        modeNamesList = new ArrayList<>();
        drawStylesList = new ArrayList<>();

        clockAdapter = new ArrayAdapter<>(this, R.layout.spinner_row, clockFacesList);
        modeAdapter = new ArrayAdapter<>(this, R.layout.spinner_row, modeNamesList);
        drawStylesAdapter = new ArrayAdapter<>(this, R.layout.spinner_row, drawStylesList);

        clockFacesSpinner.setAdapter(clockAdapter);
        modeNamesSpinner.setAdapter(modeAdapter);
        drawStylesSpinner.setAdapter(drawStylesAdapter);

        modeNamesList.add("Select Mode");
        clockFacesList.add("Select Clock");
        drawStylesList.add("Select Draw Style");

        clockAdapter.notifyDataSetChanged();
        modeAdapter.notifyDataSetChanged();
        drawStylesAdapter.notifyDataSetChanged();

        // TODO: these are async calls so will return before they actually update the spinners
        // get the various lists and add them to the spinner controls
        fetchModeNames();
        fetchClockFaces();
        fetchDrawStyles();

        // TODO: this doesn't currently work as the calls to fill the mode name list is async
        // Pick a good default mode. This handles the case when the power is "off" when we start as
        // we need a valid mode to set when they hit the "on" button
        modeNamesSpinner.setSelection(1);

        // TODO: this doesn't currently work as the calls to fill the spinner controls is async
        // now update the UI with the current Kaleidoscope settings
        fetchSettings();

        // forces the app to stay in portrait mode
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // turns the kaleidoscope on and off
        powerButton.setOnClickListener(v -> {
            if (powerOn) { // if it's on turn it off
                powerButton.getBackground().mutate().setTint(ContextCompat.getColor(getApplicationContext(), R.color.powerButtonRed));
                makePostEx("Off", null, null, null, null, null, "power button onClick");
                powerOn = false;
            } else { // if it's off turn it on
                powerButton.getBackground().mutate().setTint(ContextCompat.getColor(getApplicationContext(), R.color.powerButtonBlue));
                makePostEx(modeNamesSpinner.getSelectedItem().toString(), null, null, null, null, null, "power button onClick");
                powerOn = true;
            }
        });

        colorWheelButton.setOnClickListener(v -> new ColorPickerPopup.Builder(MainActivity.this)
                .initialColor(clockColor) // Set initial color
                .enableBrightness(true) // Enable brightness slider or not
                .enableAlpha(false) // Enable alpha slider or not
                .okTitle("Choose")
                .cancelTitle("Cancel")
                .showIndicator(true)
                .showValue(false)
                .build()
                .show(v, new ColorPickerPopup.ColorPickerObserver() {
                    @Override
                    public void onColorPicked(int color) {
                        clockColor = getRgbFromHex("#" + Integer.toHexString(color));
                        makePostEx(null, null, null, null, null, clockColor, "colorWheelButton onClick");
                    }
                }));

        // changes the speed of the kaleidoscope
        speedSeekBar.setMin(0);
        speedSeekBar.setMax(255);
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean wasItUs = false;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (wasItUs) {
                    makePostEx(null, null, null, null, progress, null, "onProgressChanged speed");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                wasItUs = true;
                canFetchSettings = false;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                canFetchSettings = true;
                wasItUs = false;
            }
        });

        // changes the brightness of the kaleidoscope
        brightnessSeekBar.setMin(-255);
        brightnessSeekBar.setMax(255);
        brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean wasItUs = false;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (wasItUs) {
                    makePostEx(null, null, null, progress, null, null, "onProgressChanged brightness");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                wasItUs = true;
                canFetchSettings = false;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                canFetchSettings = true;
                wasItUs = false;
            }
        });

        // changes the mode of the kaleidoscope
        modeNamesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!parent.getItemAtPosition(position).equals("Select Mode") && !settingUp) {
                    makePostEx(modeNamesSpinner.getSelectedItem().toString(), null, null, null, null, null, "mode names spinner selection");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // changes the clock of the kaleidoscope
        clockFacesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!parent.getItemAtPosition(position).equals("Select Clock") && !settingUp) {
                    makePostEx(null, clockFacesSpinner.getSelectedItem().toString(), null, null, null, null, "clock faces spinner selection");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        drawStylesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!parent.getItemAtPosition(position).equals("Select Draw Style") && !settingUp) {
                    makePostEx(null, null, drawStylesSpinner.getSelectedItem().toString(), null, null, null, "clock faces spinner selection");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        // TODO: This needs to be updated so that a slow fetch doesn't override settings we set via makePostEx
        /*
        // Call fetchSettings every 2 seconds to update the app with the current
        // settings from the Kaleidoscope (in case another app or the physical knobs
        // have been used to make changes).
        final Handler handler = new Handler();
        final int refreshRate = 2000; // 2.0 sec

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (canFetchSettings)
                    fetchSettings();
                handler.postDelayed(this, refreshRate);
            }
        }, refreshRate);
         */
    }

    // TODO: we shouldn't need to do this here and in OnCreate
/*    @Override
    protected void onStart() {
        super.onStart();
        fetchSettings();
    }
 */

    // TODO: cancel/ignore any pending fetchSettings calls when we call makePostEx
    // TODO: do we really need to create a thread for every call? Can/should that be optimized?
    // this will prevent an old fetch from overwriting the values we just set in makePostEx
    // posts the brightness, speed, mode and clock to the Kaleidoscope REST API
    private void makePostEx(String mode, String clockFace, String drawStyle, Integer brightness, Integer speed, Integer clockColor, String s) {
        System.out.println(s);
        Thread thread = new Thread(() -> {
            if (System.currentTimeMillis() - timeOfLastPut > PUT_RATE) {
                timeOfLastPut = System.currentTimeMillis();
                try {
                    // TODO: why does this use HttpURLConnection when all the fetch calls use OkHttpClient?
                    // https://code.tutsplus.com/tutorials/android-from-scratch-using-rest-apis--cms-27117
                    // https://developer.android.com/training/volley
                    URL url = new URL("http://kaleidoscope/api/settings");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setDoOutput(true);
                    conn.setDoInput(true);

                    JSONObject jsonParam = new JSONObject();

                    if (mode != null)
                        jsonParam.put("mode", mode);
                    if (clockFace != null)
                        jsonParam.put("clockFace", clockFace);
                    if (drawStyle != null)
                        jsonParam.put("drawStyle", drawStyle);
                    if (brightness != null)
                        jsonParam.put("brightness", brightness);
                    if (speed != null)
                        jsonParam.put("speed", speed);
                    if (clockColor != null)
                        jsonParam.put("clockColor", clockColor);

                    Log.i("JSON", jsonParam.toString());
                    DataOutputStream os = new DataOutputStream(conn.getOutputStream());
                    os.writeBytes(jsonParam.toString());

                    os.flush();
                    os.close();

                    Log.i("STATUS", String.valueOf(conn.getResponseCode()));
                    Log.i("MSG", conn.getResponseMessage());

                    conn.disconnect();

                } catch (IOException | JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
    }

    // gets the list of clock faces from the api and adds it to spinner
    private void fetchClockFaces() {

        String url = "http://kaleidoscope/api/faces";

        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                String mMessage = e.getMessage();
                Log.w("failure Response", mMessage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                if (response.isSuccessful()) {
                    final String mMessage = response.body().string();

                    MainActivity.this.runOnUiThread(() -> {
                        Log.e("Response", mMessage);
                        try {
                            JSONArray faces = new JSONArray(mMessage);

                            for (int i = 0; i < faces.length(); i++) {
                                String face = faces.getString(i);
                                Log.e("clockFace", face);
                                clockFacesList.add(face);
                            }
                            clockAdapter.notifyDataSetChanged();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        });
    }

    // gets the list of mode names from the api and adds it to spinner
    private void fetchModeNames() {
        String url = "http://kaleidoscope/api/modes";

        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                String mMessage = e.getMessage();
                Log.w("failure Response", mMessage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                if (response.isSuccessful()) {
                    final String mMessage = response.body().string();

                    MainActivity.this.runOnUiThread(() -> {
                        Log.e("Response", mMessage);
                        try {
                            JSONArray modes = new JSONArray(mMessage);

                            for (int i = 0; i < modes.length(); i++) {
                                String mode = modes.getString(i);
                                Log.e("clockFace", mode);
                                modeNamesList.add(mode);
                            }
                            modeAdapter.notifyDataSetChanged();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        });
    }

    // gets the list of draw types
    private void fetchDrawStyles() {
        String url = "http://kaleidoscope/api/drawstyles";

        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                String mMessage = e.getMessage();
                Log.w("failure Response", mMessage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                if (response.isSuccessful()) {
                    final String mMessage = response.body().string();

                    MainActivity.this.runOnUiThread(() -> {
                        Log.e("Response", mMessage);
                        try {
                            JSONArray modes = new JSONArray(mMessage);

                            for (int i = 0; i < modes.length(); i++) {
                                String style = modes.getString(i);
                                Log.e("drawStyle", style);
                                drawStylesList.add(style);
                            }
                            drawStylesAdapter.notifyDataSetChanged();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        });
    }

    // gets the current list of settings and changes our values
    private void fetchSettings() {
        String url = "http://kaleidoscope/api/settings";

        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                String mMessage = e.getMessage();
                Log.w("failure Response", mMessage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                if (response.isSuccessful()) {
                    final String mMessage = response.body().string();

                    MainActivity.this.runOnUiThread(() -> {
                        Log.e("Response", mMessage);
                        try {
                            JSONObject settings = new JSONObject(mMessage);

                            String mode = settings.getString("mode");
                            modeNamesSpinner.setSelection(modeNamesList.indexOf(mode));
                            clockFacesSpinner.setSelection(clockFacesList.indexOf(settings.getString("clockFace")));
                            drawStylesSpinner.setSelection(drawStylesList.indexOf(settings.getString("drawStyle")));
                            brightnessSeekBar.setProgress(settings.getInt("brightness"));
                            speedSeekBar.setProgress(settings.getInt("speed"));
                            clockColor = (int) settings.get("clockColor");

                            // update the power button state
                            if (mode.equals("off")) {
                                powerButton.getBackground().mutate().setTint(ContextCompat.getColor(getApplicationContext(), R.color.powerButtonRed));
                                powerOn = false;
                            }
                            else {
                                powerButton.getBackground().mutate().setTint(ContextCompat.getColor(getApplicationContext(), R.color.powerButtonBlue));
                                powerOn = true;
                            }

                            settingUp = false;
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        });
    }

    private int getRgbFromHex(String hex) { //#ffffff red green blue 0-255  ->255, 0, 0 255 255 255
        int initColor = Color.parseColor(hex);
        int r = Color.red(initColor);
        int g = Color.green(initColor);
        int b = Color.blue(initColor);
        return r << 16 | g << 8 | b;
    }
}