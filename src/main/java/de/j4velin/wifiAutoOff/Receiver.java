/*
 * Copyright 2013 Thomas Hoffmann
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.j4velin.wifiAutoOff;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.widget.Toast;

/**
 * Class for receiving various events and react on them.
 */
public class Receiver extends BroadcastReceiver {

    private static final int TIMER_SCREEN_OFF = 1;
    private static final int TIMER_NO_NETWORK = 2;
    static final int TIMER_ON_AT = 3;
    static final int TIMER_OFF_AT = 4;
    static final int TIMER_ON_EVERY = 5;

    static final int TIMEOUT_NO_NETWORK = 5;
    static final int TIMEOUT_SCREEN_OFF = 10;
    static final int ON_EVERY_TIME_MIN = 120;
    static final String ON_AT_TIME = "8:00";
    static final String OFF_AT_TIME = "22:00";

    /**
     * Starts one of the timers to turn WiFi off
     *
     * @param context the context
     * @param id      TIMER_SCREEN_OFF or TIMER_NO_NETWORK
     * @param time    in min
     */
    private void startTimer(final Context context, int id, int time) {
        String action = (id == TIMER_SCREEN_OFF) ? "SCREEN_OFF_TIMER" : "NO_NETWORK_TIMER";
        Intent timerIntent =
                new Intent(context, Receiver.class).putExtra("timer", id).setAction(action);
        if (PendingIntent.getBroadcast(context, id, timerIntent, PendingIntent.FLAG_NO_CREATE) ==
                null) {
            if (Build.VERSION.SDK_INT >= 19) {
                APILevel19Wrapper.setExactTimer(context, AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 60000 * time, PendingIntent
                                .getBroadcast(context, id, timerIntent,
                                        PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE))
                        .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60000 * time,
                                PendingIntent.getBroadcast(context, id, timerIntent,
                                        PendingIntent.FLAG_UPDATE_CURRENT));
            }
            if (BuildConfig.DEBUG)
                Logger.log("timer for action " + action + " set (" + time + " minutes)");
        } else if (BuildConfig.DEBUG) {
            Logger.log("timer for action " + action + " already set");
        }
    }

    /**
     * Stops the timer
     *
     * @param context the context
     * @param id      TIMER_SCREEN_OFF or TIMER_NO_NETWORK
     * @return true, if timer was actually active
     */
    private boolean stopTimer(final Context context, int id) {
        Intent timerIntent = new Intent(context, Receiver.class).putExtra("timer", id)
                .setAction(id == TIMER_SCREEN_OFF ? "SCREEN_OFF_TIMER" : "NO_NETWORK_TIMER");
        PendingIntent pendingIntent =
                PendingIntent.getBroadcast(context, id, timerIntent, PendingIntent.FLAG_NO_CREATE);
        if (pendingIntent != null) {
            ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).cancel(pendingIntent);
            pendingIntent.cancel();
            if (BuildConfig.DEBUG) Logger.log("timer for action " +
                    (id == TIMER_SCREEN_OFF ? "SCREEN_OFF_TIMER" : "NO_NETWORK_TIMER") +
                    " canceled");
        }
        return pendingIntent != null;
    }

    /**
     * Get default shared preferences
     *
     * @param context the context
     * @return default SharedPreferences for given context
     */
    @SuppressLint("InlinedApi")
    private static SharedPreferences getSharedPreferences(final Context context) {
        String prefFileName = context.getPackageName() + "_preferences";
        return context.getSharedPreferences(prefFileName, Context.MODE_MULTI_PROCESS);
    }

    /**
     * Changes the WiFi state
     *
     * @param context the context
     * @param on      true to turn WiFi on, false to turn it off
     */
    @SuppressWarnings("deprecation")
    private static void changeWiFi(final Context context, boolean on) {
        SharedPreferences prefs = getSharedPreferences(context);
        if (on && prefs.getBoolean("airplane", true)) {
            // check for airplane mode
            if (BuildConfig.DEBUG) Logger.log("checking for airplane mode");
            try {
                if (android.os.Build.VERSION.SDK_INT >=
                        android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 ?
                        APILevel17Wrapper.isAirplaneModeOn(context) : Settings.System
                        .getInt(context.getContentResolver(), Settings.System.AIRPLANE_MODE_ON) ==
                        1) {
                    if (BuildConfig.DEBUG)
                        Logger.log("not turning wifi on because device is in airplane mode");
                    return;
                }
            } catch (final SettingNotFoundException e) {
                // not airplane setting found? Handle like not in airplane mode
                // then
                if (BuildConfig.DEBUG) Logger.log(e);
                e.printStackTrace();
            }
        }
        if (BuildConfig.DEBUG) Logger.log(on ? "turning wifi on" : "disabling wifi");
        try {
            WifiManager wm = ((WifiManager) context.getSystemService(Context.WIFI_SERVICE));
            // do we need to change at all?
            if (wm.isWifiEnabled() != on) {
                wm.setWifiEnabled(on);
            }
        } catch (Exception e) {
            Toast.makeText(context, "Can not change WiFi state: " + e.getClass().getName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("InlinedApi")
    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();
        if (BuildConfig.DEBUG) Logger.log("received: " + action);
        SharedPreferences prefs = getSharedPreferences(context);
        if (intent.hasExtra("timer")) {
            // one of the timers expired -> turn wifi off
            changeWiFi(context, false);
            stopTimer(context, intent.getIntExtra("timer", 0));
        } else if (intent.hasExtra("changeWiFi")) {
            // for "ON AT" or "OFF AT" options
            changeWiFi(context, intent.getBooleanExtra("changeWiFi", false));
            Start.createTimers(context);
        } else {
            switch (action) {
                case GeoFenceService.LOCATION_ENTERED_ACTION:
                    if (!((WifiManager) context.getSystemService(Context.WIFI_SERVICE))
                            .isWifiEnabled()) {
                        if (prefs.getBoolean("off_no_network", true)) {
                            // start the timer before actually turning on the WiFi to set the NO_NETWORK
                            // timer to at least 10 min. The set timer in the following WIFI_STATE_CHANGED_ACTION
                            // will then have no effect, as the timer is already set
                            startTimer(context, TIMER_NO_NETWORK, Math.max(10,
                                    prefs.getInt("no_network_timeout", TIMEOUT_NO_NETWORK)));
                        }
                        changeWiFi(context, true);
                    } // else: WiFi is already enabled, do nothing
                    break;
                case ScreenChangeDetector.SCREEN_OFF_ACTION:
                    if (prefs.getBoolean("off_screen_off", true)) {
                        if (!prefs.getBoolean("ignore_screen_off", false)) {
                            // screen went off -> start TIMER_SCREEN_OFF
                            startTimer(context, TIMER_SCREEN_OFF,
                                    prefs.getInt("screen_off_timeout", TIMEOUT_SCREEN_OFF));
                        } else if (BuildConfig.DEBUG)
                            Logger.log("screen is off ignored due to power connected");
                    }
                    break;
                case UnlockReceiver.USER_PRESENT_ACTION:
                case ScreenChangeDetector.SCREEN_ON_ACTION:
                    // user unlocked the device -> stop TIMER_SCREEN_OFF, might turn on
                    // WiFi
                    stopTimer(context, TIMER_SCREEN_OFF);
                    if (prefs.getBoolean("on_unlock", true)) {
                        boolean noNetTimer = stopTimer(context, TIMER_NO_NETWORK);
                        if (((WifiManager) context.getSystemService(Context.WIFI_SERVICE))
                                .isWifiEnabled()) {
                            if (noNetTimer && prefs.getBoolean("off_no_network", true)) {
                                // if WiFi is already turned on, just restart the NO_NETWORK timer
                                startTimer(context, TIMER_NO_NETWORK,
                                        prefs.getInt("no_network_timeout", TIMEOUT_NO_NETWORK));
                            }
                        } else {
                            changeWiFi(context, true);
                        }
                    }
                    break;
                case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                    final NetworkInfo nwi =
                            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (nwi == null) return;
                    if (BuildConfig.DEBUG)
                        Logger.log("network state changed: " + nwi.getState().name());
                    if (nwi.isConnected()) {
                        stopTimer(context, TIMER_NO_NETWORK);
                    } else if (nwi.getState().equals(NetworkInfo.State.DISCONNECTED) ||
                            nwi.getState().equals(NetworkInfo.State.DISCONNECTING)) {
                        if (prefs.getBoolean("off_no_network", true)) {
                            startTimer(context, TIMER_NO_NETWORK,
                                    prefs.getInt("no_network_timeout", TIMEOUT_NO_NETWORK));
                        }
                    }
                    break;
                case WifiManager.WIFI_STATE_CHANGED_ACTION:
                    if (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED) {
                        if (BuildConfig.DEBUG) Logger.log("wifi state changed: wifi enabled");
                        if (prefs.getBoolean("off_no_network", true)) {
                            startTimer(context, TIMER_NO_NETWORK,
                                    prefs.getInt("no_network_timeout", TIMEOUT_NO_NETWORK));
                        }
                        if (prefs.getBoolean("off_screen_off", true) &&
                                ((Build.VERSION.SDK_INT < 20 && !((PowerManager) context
                                        .getSystemService(Context.POWER_SERVICE)).isScreenOn()) ||
                                        (Build.VERSION.SDK_INT >= 20 &&
                                                !APILevel20Wrapper.isScreenOn(context)))) {
                            startTimer(context, TIMER_SCREEN_OFF,
                                    prefs.getInt("screen_off_timeout", TIMEOUT_SCREEN_OFF));
                        }
                    } else if (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_DISABLED) {
                        if (BuildConfig.DEBUG)
                            Logger.log("wifi state changed: wifi disabled -> clear timer");
                        stopTimer(context, TIMER_SCREEN_OFF);
                        stopTimer(context, TIMER_NO_NETWORK);
                    }
                    break;
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                    // wifi direct connection changed
                    NetworkInfo networkInfo =
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    WifiP2pInfo winfo =
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                    if (BuildConfig.DEBUG)
                        Logger.log("new P2P network state: " + networkInfo.getState());
                    if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 14)
                        Logger.log("P2P group formed: " + APILevel14Wrapper.groupFormed(winfo));
                    if (networkInfo.isConnected() ||
                            (Build.VERSION.SDK_INT >= 14 && APILevel14Wrapper.groupFormed(winfo))) {
                        stopTimer(context, TIMER_NO_NETWORK);
                    } else {
                        if (prefs.getBoolean("off_no_network", true)) {
                            startTimer(context, TIMER_NO_NETWORK,
                                    prefs.getInt("no_network_timeout", TIMEOUT_NO_NETWORK));
                        }
                    }
                    break;
                case Intent.ACTION_POWER_CONNECTED:
                    // connected to external power supply
                    if (BuildConfig.DEBUG) Logger.log("power connected setting: " +
                            prefs.getBoolean("power_connected", false));
                    if (prefs.getBoolean("power_connected", false)) {
                        changeWiFi(context, true);
                        if (prefs.getBoolean("off_screen_off",
                                true)) { // ignore display off events while charging
                            stopTimer(context, TIMER_SCREEN_OFF);
                            prefs.edit().putBoolean("ignore_screen_off", true).apply();
                            if (BuildConfig.DEBUG) Logger.log("ignore screen off events");
                        }
                    }
                    break;
                case Intent.ACTION_POWER_DISCONNECTED:
                    // disconnected from external power supply
                    if (BuildConfig.DEBUG) Logger.log("power connected setting: " +
                            prefs.getBoolean("power_connected", false));
                    // do we need to start the screen off timer?
                    if (prefs.getBoolean("power_connected", false) &&
                            prefs.getBoolean("off_screen_off", true)) {
                        prefs.edit().putBoolean("ignore_screen_off", false).apply();
                        if (BuildConfig.DEBUG) Logger.log("dont ignore screen off event any more");
                        if ((Build.VERSION.SDK_INT < 20 &&
                                !((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                                        .isScreenOn()) || (Build.VERSION.SDK_INT >= 20 &&
                                !APILevel20Wrapper.isScreenOn(context))) {
                            if (BuildConfig.DEBUG) Logger.log("screen is off -> start timer");
                            startTimer(context, TIMER_SCREEN_OFF,
                                    prefs.getInt("screen_off_timeout", TIMEOUT_SCREEN_OFF));
                        }
                    }
                    break;
            }
        }
    }
}
