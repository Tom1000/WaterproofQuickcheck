/*      Copyright 2015 Tom Roida

        Licensed under the Apache License, Version 2.0 (the "License");
        you may not use this file except in compliance with the License.
        You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

        Unless required by applicable law or agreed to in writing, software
        distributed under the License is distributed on an "AS IS" BASIS,
        WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
        See the License for the specific language governing permissions and
        limitations under the License.
*/


package de.roida.app.WaterproofQuickcheck;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import com.androidplot.util.PlotStatistics;
import com.androidplot.xy.*;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.RatingBar;


public class DetailsActivity extends AppCompatActivity implements SensorEventListener {

    SharedPreferences SP;                                   // Preferences from "Settings" activity;

    private double pressureDeviationMax;                    // max pressure deviation allowed from ideal curve. Area: time * delta pressure
    private long slideMinTime;                              // how long must the user press the slider at least? (in milliseconds)
    private static final int HISTORY_SIZE = 30;             // number of points to plot in history
    private long pressureDropFactor = 0;                    // adapt the "ideal" curve
    private long MeasureTime;                               // time of measurement after low peak detection in millisec
    private SensorManager sensorMgr = null;
    private Sensor orSensor = null;
    private TextView TextViewResult, TextViewRating;
    private String ResultText ="";
    private RatingBar ratingBarTom;
    private XYPlot aprHistoryPlot = null;
    private LineAndPointFormatter aprFormatter, idealFormatter;
    private SimpleXYSeries pressureHistorySeries = null;
    private SimpleXYSeries pressureIdealSeries = null;
    private SeekBar swiperTom ;                             // The seekBar to be pressed to increase the pressure inside the device
    private float rawValue = 0;
    private float maxValue = 0;                             // while pressing, detect the max pressure value building up
    private float minValue = 0;                             // after pressing, detect the min pressure in the device
    private long  minValueTimestamp, progressTimestamp;     //...and the timestamp in nanoseconds. (Divide by 1000 000 000 to get seconds.)
    private boolean minValueDetect = false;                 // currently no detection of min peak in progress
    private int risingValuesCounter = 0;
    private double pressureDeviation = 0;                   // the area of the plannedPressureCurve - actualPressureCurve
    private double onSensorChangedLastTimestamp = 0;
    private long SysNanoTimeTarget = 0;
    private boolean currentlyOnDetails = false;             // has the user switched to the "Details" screen?
    private float timeConstant;                             // Low pass filter constant (in seconds)
    private float alpha = 0.0f;
    private long timestamp = System.nanoTime();             // time stamps for low pass filter
    private long timestampOld = System.nanoTime();          // time of measurement after low peak detection in millisec
    private float filteredSensorValue = 1000;                            // initial setup of filter output (in mbar)
    private int count = 1;


    /** ----------------------------------------------------------------------------------------- */



    @Override
    protected void onCreate(Bundle savedInstanceState) { //Called when the activity is first created
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_details);

        // load preferences
        SP = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
        reloadPrefs();

        // setup the plot:
        aprHistoryPlot = (XYPlot) findViewById(R.id.plot);

        pressureHistorySeries = new SimpleXYSeries("Measured Pressure");
        pressureHistorySeries.useImplicitXVals();

        pressureIdealSeries = new SimpleXYSeries("Ideal pressure");
        pressureIdealSeries.useImplicitXVals();

        aprHistoryPlot.setRangeBoundaries(-1, 1, BoundaryMode.FIXED);
        aprHistoryPlot.setDomainBoundaries(0, 30, BoundaryMode.AUTO);

        aprFormatter = new LineAndPointFormatter(Color.rgb(100, 100, 200), Color.BLACK, null, null); //line, point, fill
        idealFormatter = new LineAndPointFormatter(Color.rgb(200, 80, 80), Color.RED, null, null);

        aprHistoryPlot.addSeries(pressureHistorySeries, aprFormatter);
        aprHistoryPlot.addSeries(pressureIdealSeries, idealFormatter);

        aprHistoryPlot.setDomainStepValue(5);
        aprHistoryPlot.setTicksPerRangeLabel(3);
        aprHistoryPlot.setDomainLabel("Sample Index");
        aprHistoryPlot.getDomainLabelWidget().pack();
        aprHistoryPlot.setRangeLabel("Pressure delta (mbar)");
        aprHistoryPlot.getRangeLabelWidget().pack();

        TextViewResult = (TextView) findViewById(R.id.textViewResult);
        TextViewResult.setMovementMethod(new ScrollingMovementMethod());
        TextViewRating = (TextView) findViewById(R.id.textViewRating);

        swiperTom=(SeekBar) findViewById(R.id.swiper);
        ratingBarTom = (RatingBar) findViewById(R.id.ratingBar);

        final PlotStatistics histStats = new PlotStatistics(1000, false);

        aprHistoryPlot.addListener(histStats);

        swiperTom.setEnabled(true);
        ratingBarTom.setEnabled(false);
        aprHistoryPlot.setVisibility(View.INVISIBLE);
        TextViewResult.setVisibility(View.INVISIBLE);

        // register for orientation sensor events:
        sensorMgr = (SensorManager) getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        for (Sensor sensor : sensorMgr.getSensorList(Sensor.TYPE_PRESSURE)) {
            if (sensor.getType() == Sensor.TYPE_PRESSURE) {
                orSensor = sensor;
            }
        }

        // if we can't access the sensor then exit:
        if (orSensor == null) {
            System.out.println("Failed to attach to Sensor.");
            TextViewResult.setEnabled(true);
            TextViewResult.setVisibility(View.VISIBLE);
            TextViewResult.setText("ERROR: No pressure sensor detected! This app will not work on this device!");

        } else
            sensorMgr.registerListener(this, orSensor, SensorManager.SENSOR_DELAY_NORMAL);


        //Now set the SeekBar
        swiperTom.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            int progress = 0;

            @Override
            public void onProgressChanged(SeekBar swiperTom, int progressValue, boolean fromUser) {
                progress = progressValue;
                if (fromUser) {
                    if (maxValue < rawValue - filteredSensorValue) {
                        maxValue = rawValue - filteredSensorValue;
                    }
                }
                double slideTimeElapsed = (System.nanoTime() - progressTimestamp) / 1000000; //in millisec
                double tw1 = slideTimeElapsed /slideMinTime * 100;
                if ((progressValue > (tw1))) {               //slow down
                    swiperTom.setProgress((int) (tw1 + 0.5)); //set progress to max allowed value for this moment in time
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                progressTimestamp = System.nanoTime();
                ratingBarTom.setRating(0);
                ratingBarTom.setEnabled(false);
                if ((filteredSensorValue - rawValue < 0.5) && (filteredSensorValue - rawValue > -0.5)) {
                    ResultText += "Measuring..." + "\n";
                    TextViewResult.setText(ResultText);
                    TextViewRating.setText("Measuring...");
                    maxValue = 0;
                } else {
                    ResultText += "Error: pressure out of expected range +/-0.5" + "\n";
                    TextViewResult.setText(ResultText);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar swiperTom) {
                if (swiperTom.getProgress() > 90) {
                    ratingBarTom.setEnabled(true);
                    if (maxValue > 0.3) {
                        ResultText += "done: Peak was " + Float.toString(maxValue) + "  good" + "\n";
                        TextViewResult.setText(ResultText);
                        minValue = maxValue; // now we start the detection of the low peak!
                        minValueDetect = true;
                        swiperTom.setEnabled(false);
                    } else {
                        ResultText += "done: Peak was " + Float.toString(maxValue) + "  bad" + "\n";
                        TextViewResult.setText(ResultText);
                        TextViewRating.setText("No pressure peak could be detected. Do NOT expose device to water!");
                    }
                } else {
                    ResultText += "Aborted: you did not swipe long enough!" + "\n";
                    TextViewResult.setText(ResultText);
                    TextViewRating.setText("");
                }
                swiperTom.setProgress(0);
            }
        });
    }

    // Called whenever a new orSensor reading is taken.
    @Override
    public synchronized void onSensorChanged(SensorEvent sensorEvent) {
        double ideal = 0;
        long SysNanoTimeNow = System.nanoTime();
        double tneu   = (SysNanoTimeNow-minValueTimestamp           )/1000000; // in milliseconds since minValue
        double deltaT = (SysNanoTimeNow-onSensorChangedLastTimestamp)/1000000; // in milliseconds since last onSensorChanged event;
        onSensorChangedLastTimestamp = SysNanoTimeNow;

        // get rid the oldest sample in history:
        if (pressureHistorySeries.size() > HISTORY_SIZE) {
            pressureHistorySeries.removeFirst();
        }
        if (pressureIdealSeries.size() > HISTORY_SIZE) {
            pressureIdealSeries.removeFirst();
        }

        int sw1 = swiperTom.getProgress();
        if ((sw1==0) && !minValueDetect && (risingValuesCounter==0)) //no peak detection and no measuring in progress
            lowPass(sensorEvent.values[0]); // writes the filtered low-pass-value into "filteredSensorValue"


        // add the latest history sample:
        pressureHistorySeries.addLast(null, sensorEvent.values[0] - filteredSensorValue);
        pressureIdealSeries  .addLast(null, 9999); // do not draw because out of range

        // if the low peak detection is in progress, detect it!
        if (risingValuesCounter>0) {
            risingValuesCounter++;
        } else
        if (minValueDetect) {
            if ((sensorEvent.values[0]-filteredSensorValue)<minValue) {
                minValue = sensorEvent.values[0] - filteredSensorValue; // there is a lower value
                minValueTimestamp = SysNanoTimeNow;
                SysNanoTimeTarget = SysNanoTimeNow + MeasureTime*1000000;
            }else{ // take the last value as low peak! Now start the rising pressure  curve analysis!
                // look for something like this:
                //                                      *   *   *    *
                //                              *
                //                      *
                //               *
                //           *
                //        *
                //       *
                ratingBarTom.setRating(1);
                // now collect the values in an array until leveling
                risingValuesCounter = 1;
                pressureIdealSeries.removeLast();
                pressureIdealSeries.removeLast();
                pressureIdealSeries.addLast(null, minValue);
                ideal = -1/((tneu*tneu/pressureDropFactor+1/(-minValue)));
                pressureIdealSeries.addLast(null, ideal);
                pressureDeviation = 0;
                pressureDeviation = Math.abs((ideal-(sensorEvent.values[0] - filteredSensorValue)) * deltaT/1000); // =  ((ideal-actual)* Delta T)
            }
        }

        //evaluate and reset if full
        if (risingValuesCounter>1){
            ideal = -1/((tneu*tneu/pressureDropFactor+1/(-minValue)));
            pressureIdealSeries.removeLast();
            pressureIdealSeries.addLast(null, ideal);
            pressureDeviation = pressureDeviation + Math.abs((ideal-(sensorEvent.values[0] - filteredSensorValue)) * deltaT/1000); // =  ((ideal-actual)* Delta T)
            if (SysNanoTimeNow>SysNanoTimeTarget){  //final evaluation + reset if full
                // final evaluation:
                // star #1: min peak could be detected
                // star #2: no significant deviation from ideal curve

                float rating = 1+Math.round((float) pressureDeviationMax/(float) pressureDeviation);
                ResultText += "Rating: " + Float.toString(rating) + "\n"; TextViewResult.setText(ResultText);
                ratingBarTom.setRating(rating);
                ResultText += "Measured deviation of pressure over time vs. expected curve: " + Double.toString(pressureDeviation) + "\n";
                TextViewResult.setText(ResultText);
                if (rating > 1.7) {
                    //OK!
                    ResultText += "OK - Device looks to be sealed" + "\n"; TextViewResult.setText(ResultText);
                    TextViewRating.setText("OK - Device looks to be sealed!");
                } else {
                    //not OK!
                    ResultText += "Not OK - Device looks to be NOT sealed. Do not expose to water!" + "\n"; TextViewResult.setText(ResultText);
                    TextViewRating.setText("Not OK - Device looks NOT to be  sealed. Do not expose to water!");
                }

                resetMeasurement();// reset measurement

            }
        }


        aprHistoryPlot.redraw();// redraw the Plot
        TextView TextViewHead = (TextView) findViewById(R.id.textViewHead);
        if (currentlyOnDetails)
            TextViewHead.setText("Pressure sensor - raw value: " + String.format("%.4f", sensorEvent.values[0])+"   filtered: " + String.format("%.4f", filteredSensorValue));
        else
            TextViewHead.setText("Pressure sensor");
        rawValue = sensorEvent.values[0];
    }

    public void resetMeasurement(){
        risingValuesCounter = 0;
        minValue = 0;
        maxValue = 0;
        minValueDetect=false;
        pressureDeviation = 0;
        swiperTom.setEnabled(true);
        timestamp = System.nanoTime();
        timestampOld = System.nanoTime();

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        // Not interested in this event
    }

    public boolean openAboutDialog (MenuItem item){
        Context context = getApplicationContext();
        PackageManager packageManager = context.getPackageManager();
        String packageName = context.getPackageName();
        String versionName = "not available"; // initialize String
        try {
            versionName = packageManager.getPackageInfo(packageName, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        String aboutMessage = getResources().getString(R.string.about_message);
        aboutMessage = "Version: " + versionName + "\n" + aboutMessage;
        builder.setMessage(aboutMessage)
                .setTitle(R.string.about_title);
        AlertDialog dialog = builder.create();
        dialog.show();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_details, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_detailedView) {
            if (!currentlyOnDetails) {
                aprHistoryPlot.setVisibility(View.VISIBLE);
                TextViewResult.setVisibility(View.VISIBLE);
                TextView disclaimer = (TextView)findViewById(R.id.disclaimer);
                disclaimer.setEnabled(false);
                disclaimer.setVisibility(View.INVISIBLE);
                currentlyOnDetails = true;
                return true;
            } else {
                currentlyOnDetails = false;
                aprHistoryPlot.setVisibility(View.INVISIBLE);
                TextViewResult.setVisibility(View.INVISIBLE);
                TextView disclaimer = (TextView) findViewById(R.id.disclaimer);
                disclaimer.setEnabled(true);
                disclaimer.setVisibility(View.VISIBLE);
            }
        }
        if (id == R.id.action_settings) {
            Intent intent = new Intent(DetailsActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void onPause() {
        super.onPause();
        sensorMgr.unregisterListener(this);
        resetMeasurement();// reset measurement
        //TODO test this
    }

    protected void onResume() {
        super.onResume();
        sensorMgr.registerListener(this, orSensor, SensorManager.SENSOR_DELAY_UI);
        timestampOld=System.nanoTime();
        reloadPrefs();
    }


    public void lowPass(float input)
    {
        timestamp = System.nanoTime();

        // Find the sample period (between updates).
        // Convert from nanoseconds to seconds
        float dt = 1 / (count / ((timestamp - timestampOld) / 1000000000.0f));

        // Calculate alpha
        alpha = timeConstant / (timeConstant + dt);
        filteredSensorValue = alpha * filteredSensorValue + (1 - alpha) * input; //TODO when measuring ends, there is a step in the graph...?!?!
        timestampOld = timestamp;
    }

    @Override
    public void onBackPressed() {
        if (currentlyOnDetails) {
            currentlyOnDetails = false;
            aprHistoryPlot.setVisibility(View.INVISIBLE);
            TextViewResult.setVisibility(View.INVISIBLE);
            TextView disclaimer = (TextView)findViewById(R.id.disclaimer);
            disclaimer.setEnabled(true);
            disclaimer.setVisibility(View.VISIBLE);
        } else
            finish();
    }

    private void reloadPrefs () {
        pressureDeviationMax = Float.parseFloat(SP.getString("pref_max_deviation", "9999"));
        slideMinTime = Integer.parseInt(SP.getString("pref_slide_min_time", "9999"));
        MeasureTime = Long.parseLong(SP.getString("pref_measure_time", "9999"));
        timeConstant = Float.parseFloat(SP.getString("pref_time_constant", "9999"));
        pressureDropFactor = Long.parseLong(SP.getString("pref_ideal_pressure_drop", "0"));
        System.out.println("PrefsLoaded.   " + Long.toString(slideMinTime)); //TODO ?
    }



}
