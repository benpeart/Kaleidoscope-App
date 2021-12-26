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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Call;
import okhttp3.Callback;

import top.defaults.colorpicker.ColorPickerPopup;

public class MainActivity extends AppCompatActivity {

    OkHttpClient client = new OkHttpClient();
    public String baseURL = "http://kaleidoscope/api";
    public static final String fetchTAG = "fetch";

    // TODO: replace this logic with logic that prevents updating any UI control that is currently being interacted with.
    // It should also prevent sending changes via makePostEx as a result of the fetchSettings call.
    CountDownLatch doneSignal = new CountDownLatch(3);
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

        clockAdapter.notifyDataSetChanged();
        modeAdapter.notifyDataSetChanged();
        drawStylesAdapter.notifyDataSetChanged();

        // asynchronously get the various arrays of names and styles and add them to the spinner controls
        fetchModeNames();
        fetchClockFaces();
        fetchDrawStyles();

        // TODO: handle landscape mode as well
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
                // don't change the mode if the power is 'off'
                if (powerOn && !settingUp) {
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
                if (!settingUp) {
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
                if (!settingUp) {
                    makePostEx(null, null, drawStylesSpinner.getSelectedItem().toString(), null, null, null, "clock faces spinner selection");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        // Call fetchSettings every 2 seconds to update the app with the current
        // settings from the Kaleidoscope (in case another app or the physical knobs
        // have been used to make changes).
        final Handler handler = new Handler();
        final int refreshRate = 500; // ms

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (canFetchSettings)
                    fetchSettings();
                handler.postDelayed(this, refreshRate);
            }
        }, refreshRate);
    }

    // onStart() is called when the activity is becoming visible to the user
    @Override
    protected void onStart() {
        super.onStart();

        // block until the fetchModeNames(), fetchClockFaces, fetchDrawStyles calls complete
        try {
            doneSignal.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            // TODO: handle the timeout exception
        }

        // Update the UI with the current Kaleidoscope settings
        fetchSettings();
    }

    // TODO: cancel/ignore any pending fetchSettings calls when we call makePostEx
    // TODO: do we really need to create a thread for every call? Can/should that be optimized?
    // this will prevent an old fetch from overwriting the values we just set in makePostEx
    // posts the brightness, speed, mode and clock to the Kaleidoscope REST API
    private void makePostEx(String mode, String clockFace, String drawStyle, Integer brightness, Integer speed, Integer clockColor, String s) {
        System.out.println(s);
        Thread thread = new Thread(() -> {
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
        });
        thread.start();
    }

    // gets the list of clock faces from the api and adds it to spinner
    private void fetchClockFaces() {
        String url = baseURL + "/faces";

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
                doneSignal.countDown();
            }
        });
    }

    // gets the list of mode names from the api and adds it to spinner
    private void fetchModeNames() {
        String url = baseURL + "/modes";

        Request request = new Request.Builder()
                .url(url)
                .tag(fetchTAG)
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
                doneSignal.countDown();
            }
        });
    }

    // gets the list of draw types
    private void fetchDrawStyles() {
        String url = baseURL + "/drawstyles";

        Request request = new Request.Builder()
                .url(url)
                .tag(fetchTAG)
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
                doneSignal.countDown();
            }
        });
    }

    // gets the current list of settings and changes our values
    private void fetchSettings() {
        String url = baseURL + "/settings";

        Request request = new Request.Builder()
                .url(url)
                .tag(fetchTAG)
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

    private void cancelCallWithTag(OkHttpClient client, String tag) {
        // A call may transition from queue -> running. Remove queued Calls first.
        for(Call call : client.dispatcher().queuedCalls()) {
            if(call.request().tag().equals(tag))
                call.cancel();
        }
        for(Call call : client.dispatcher().runningCalls()) {
            if(call.request().tag().equals(tag))
                call.cancel();
        }
    }
}