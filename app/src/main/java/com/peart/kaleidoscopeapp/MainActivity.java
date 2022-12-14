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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;

import top.defaults.colorpicker.ColorPickerPopup;

public class MainActivity extends AppCompatActivity {

    // the maximum rate of posting to the api
    private static final long POST_RATE = 256; //every n ms
    private long timeOfLastPOST = 0;

    OkHttpClient client = new OkHttpClient();
    public static final String baseURL = "http://kaleidoscope/api";
    public static final String fetchSettingsTAG = "fetch";

    // Ensure the UI spinners have been populated before we initialize their state from fetchSettings
    CountDownLatch doneSignal = new CountDownLatch(3);

    // track the power on/off state and clock color here as they don't have persistent state in the UI controls
    boolean powerOn = true;
    String clockColor = "#FFFFFF";

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
                makePost("Off", null, null, null, null, null, "power button onClick");
                powerOn = false;
            } else { // if it's off turn it on
                powerButton.getBackground().mutate().setTint(ContextCompat.getColor(getApplicationContext(), R.color.powerButtonBlue));
                makePost(modeNamesSpinner.getSelectedItem().toString(), null, null, null, null, null, "power button onClick");
                powerOn = true;
            }
        });

        colorWheelButton.setOnClickListener(v -> new ColorPickerPopup.Builder(MainActivity.this)
                .initialColor(Color.parseColor(clockColor)) // Set initial color
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
                        makePost(null, null, null, null, null, color, "colorWheelButton onClick");
                    }
                }));

        // changes the speed of the kaleidoscope
        speedSeekBar.setMin(0);
        speedSeekBar.setMax(255);
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                // dragging the slider can cause a flood of events
                // throttle our POST calls so we don't overwhelm the Kaleidoscope
                if (System.currentTimeMillis() < timeOfLastPOST + POST_RATE)
                    return;
                timeOfLastPOST = System.currentTimeMillis();

                makePost(null, null, null, null, progress, null, "onProgressChanged speed");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                makePost(null, null, null, null, seekBar.getProgress(), null, "onStopTrackingTouch speed");
            }
        });

        // changes the brightness of the kaleidoscope
        brightnessSeekBar.setMin(-255);
        brightnessSeekBar.setMax(255);
        brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                // dragging the slider can cause a flood of events
                // throttle our POST calls so we don't overwhelm the Kaleidoscope
                if (System.currentTimeMillis() < timeOfLastPOST + POST_RATE)
                    return;
                timeOfLastPOST = System.currentTimeMillis();

                makePost(null, null, null, progress, null, null, "onProgressChanged brightness");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                makePost(null, null, null, seekBar.getProgress(), null, null, "onStopTrackingTouch brightness");
            }
        });

        // changes the mode of the kaleidoscope
        modeNamesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // don't change the mode if the power is 'off'
                if (powerOn) {
                    makePost(modeNamesSpinner.getSelectedItem().toString(), null, null, null, null, null, "mode names spinner selection");
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
                makePost(null, clockFacesSpinner.getSelectedItem().toString(), null, null, null, null, "clock faces spinner selection");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        drawStylesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                makePost(null, null, drawStylesSpinner.getSelectedItem().toString(), null, null, null, "clock styles spinner selection");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        // Call fetchSettings every 2 seconds to update the app with the current
        // settings from the Kaleidoscope (in case another app or the physical knobs
        // have been used to make changes).
/*
        final Handler handler = new Handler();
        final int refreshRate = 2000; // 2.0 sec

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                fetchSettings();
                handler.postDelayed(this, refreshRate);
            }
        }, refreshRate);
*/
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

    // posts the brightness, speed, mode and clock to the Kaleidoscope REST API
    private void makePost(String mode, String clockFace, String drawStyle, Integer brightness, Integer speed, Integer clockColor, String s) {

        Log.i("makePost", s);

        // cancel any pending fetch requests so that they don't overwrite what we're about to set.
        cancelCallWithTag(client, fetchSettingsTAG);

        // assemble the JSON object
        JSONObject json = new JSONObject();
        try {

            if (mode != null)
                json.put("mode", mode);
            if (clockFace != null)
                json.put("clockFace", clockFace);
            if (drawStyle != null)
                json.put("drawStyle", drawStyle);
            if (brightness != null)
                json.put("brightness", brightness);
            if (speed != null)
                json.put("speed", speed);
            if (clockColor != null)
                json.put("clockColor", clockColor);

            Log.i("makePost::JSON", json.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // create the request (use getBytes so that okhttp3 doesn't append the character encoding
        // causing a 500 error as the Kaleidoscope doesn't handle encoding strings properly).
        // see https://github.com/square/okhttp/issues/2099 for details
        RequestBody body = RequestBody.create(json.toString().getBytes(StandardCharsets.UTF_8), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(baseURL + "/settings")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                Log.e("makePost::onFailure: ", e.getMessage());
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                Log.i("makePost::onResponse", String.valueOf(response.code() + " " + response.networkResponse().message()));
                response.close();
            }
        });
    }

    // gets the list of clock faces from the api and adds it to spinner
    private void fetchClockFaces() {
        String url = baseURL + "/faces";

        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                String mMessage = e.getMessage();
                Log.e("fetchClockFaces::onFailure", mMessage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                try (ResponseBody responseBody = response.body()) {

                    if (response.isSuccessful()) {
                        String mMessage = responseBody.string();

                        MainActivity.this.runOnUiThread(() -> {
                            Log.i("fetchClockFaces::onResponse", mMessage);
                            try {
                                JSONArray faces = new JSONArray(mMessage);

                                for (int i = 0; i < faces.length(); i++) {
                                    clockFacesList.add(faces.getString(i));
                                }
                                clockAdapter.notifyDataSetChanged();
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                }

                // decrement the count of fetch tasks that need to complete before we call fetchSettings
                doneSignal.countDown();
            }
        });
    }

    // gets the list of mode names from the api and adds it to spinner
    private void fetchModeNames() {
        String url = baseURL + "/modes";

        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                String mMessage = e.getMessage();
                Log.e("failure Response", mMessage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                try (ResponseBody responseBody = response.body()) {

                    if (response.isSuccessful()) {
                        String mMessage = responseBody.string();

                        MainActivity.this.runOnUiThread(() -> {
                            Log.i("fetchModeNames::onResponse", mMessage);
                            try {
                                JSONArray modes = new JSONArray(mMessage);

                                for (int i = 0; i < modes.length(); i++) {
                                    modeNamesList.add(modes.getString(i));
                                }
                                modeAdapter.notifyDataSetChanged();
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                }

                // decrement the count of fetch tasks that need to complete before we call fetchSettings
                doneSignal.countDown();
            }
        });
    }

    // gets the list of draw types
    private void fetchDrawStyles() {
        String url = baseURL + "/drawstyles";

        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                String mMessage = e.getMessage();
                Log.e("failure Response", mMessage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                try (ResponseBody responseBody = response.body()) {

                    if (response.isSuccessful()) {
                        String mMessage = responseBody.string();

                        MainActivity.this.runOnUiThread(() -> {
                            Log.i("fetchDrawStyles::onResponse", mMessage);
                            try {
                                JSONArray modes = new JSONArray(mMessage);

                                for (int i = 0; i < modes.length(); i++) {
                                    drawStylesList.add(modes.getString(i));
                                }
                                drawStylesAdapter.notifyDataSetChanged();
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                }

                // decrement the count of fetch tasks that need to complete before we call fetchSettings
                doneSignal.countDown();
            }
        });
    }

    // gets the current list of settings and changes our values
    private void fetchSettings() {
        String url = baseURL + "/settings";

        Request request = new Request.Builder()
                .url(url)
                .tag(fetchSettingsTAG)
                .header("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                String mMessage = e.getMessage();
                Log.e("fetchSettings::onFailure", mMessage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {

                try (ResponseBody responseBody = response.body()) {

                    if (response.isSuccessful()) {
                        String mMessage = responseBody.string();

                        MainActivity.this.runOnUiThread(() -> {
                            Log.i("fetchSettings::onResponse", mMessage);
                            try {
                                JSONObject settings = new JSONObject(mMessage);

                                // update the UI to reflect the current Kaleidoscope settings
                                String mode = settings.getString("mode");
                                modeNamesSpinner.setSelection(modeNamesList.indexOf(mode));
                                clockFacesSpinner.setSelection(clockFacesList.indexOf(settings.getString("clockFace")));
                                drawStylesSpinner.setSelection(drawStylesList.indexOf(settings.getString("drawStyle")));
                                brightnessSeekBar.setProgress(settings.getInt("brightness"));
                                speedSeekBar.setProgress(settings.getInt("speed"));
                                clockColor = settings.getString("clockColor");

                                // update the power button state
                                if (mode.equals("off")) {
                                    powerButton.getBackground().mutate().setTint(ContextCompat.getColor(getApplicationContext(), R.color.powerButtonRed));
                                    powerOn = false;
                                }
                                else {
                                    powerButton.getBackground().mutate().setTint(ContextCompat.getColor(getApplicationContext(), R.color.powerButtonBlue));
                                    powerOn = true;
                                }
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        });
                    } else
                        responseBody.close();
                }
            }
        });
    }

    private void cancelCallWithTag(OkHttpClient client, String tag) {

        if (client == null || tag == null)
            return;

        // A call may transition from queue -> running. Remove queued Calls first.
        for (Call call : client.dispatcher().queuedCalls()) {
            if (call.request().tag() != null && call.request().tag().equals(tag)) {
                Log.w("cancelCallWithTag::queuedCalls", "");
                call.cancel();
            }
        }
        for (Call call : client.dispatcher().runningCalls()) {
            if (call.request().tag() != null && call.request().tag().equals(tag)) {
                Log.w("cancelCallWithTag::queuedCalls", "");
                call.cancel();
            }
        }
    }
}