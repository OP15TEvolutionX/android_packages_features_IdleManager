/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.lunaris;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.UidObserver;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LunarisIdleManager {

    private static final String TAG = "LunarisIdleManager";

    private static final String ACTION_SCAN = "com.android.systemui.lunaris.ACTION_IDLE_SCAN";
    private static final String SYSTEMUI_PERMISSION = "com.android.systemui.permission.SELF";
    private static final int PI_SCAN_REQUEST = 0x4C494D01;

    private static final int MIN_TIMEOUT_MINUTES = 1;
    private static final int MAX_TIMEOUT_MINUTES = 240;

    private static final long MIN_DELAY_MS = 100L;
    private static final long INITIAL_SCAN_DELAY_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long FINAL_SCAN_MARGIN_MS = TimeUnit.SECONDS.toMillis(1);

    public static final int STANDBY_BUCKET_ACTIVE = 10;
    public static final int STANDBY_BUCKET_WORKING_SET = 20;
    public static final int STANDBY_BUCKET_FREQUENT = 30;
    public static final int STANDBY_BUCKET_RARE = 40;
    public static final int STANDBY_BUCKET_RESTRICTED = 45;

    private volatile long mCycleStartedElapsedMs;
    private volatile int mCycleGeneration;
    private volatile int mScansCompleted;

    public enum IdleAction {
        STANDBY_BUCKET_RARE,
        STANDBY_BUCKET_RESTRICTED,
        KILL_BACKGROUND,
        FULL_KILL;

        public String toDisplayName() {
            switch (this) {
                case STANDBY_BUCKET_RARE:
                    return "Rare Bucket";
                case STANDBY_BUCKET_RESTRICTED:
                    return "Restricted";
                case KILL_BACKGROUND:
                    return "Kill BG";
                case FULL_KILL:
                    return "Full Kill";
                default:
                    return name();
            }
        }
    }

    public static final class AppConfig {
        public final String packageName;
        public final IdleAction action;

        public AppConfig(@NonNull String packageName, @NonNull IdleAction action) {
            this.packageName = packageName;
            this.action = action;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("package", packageName);
            o.put("action", action.name());
            return o;
        }

        public static AppConfig fromJson(@NonNull JSONObject o) throws Exception {
            String pkg = o.getString("package");
            IdleAction act = parseAction(o.optString("action",
                    IdleAction.STANDBY_BUCKET_RARE.name()));
            return new AppConfig(pkg, act);
        }

        private static IdleAction parseAction(String s) {
            try   { return IdleAction.valueOf(s); }
            catch (Exception e) { return IdleAction.STANDBY_BUCKET_RARE; }
        }
    }

    public static final class AppEnforcementRecord {
        public final String packageName;
        public final IdleAction actionTaken;
        public final long timestampMs;
        public final int killCount;

        public AppEnforcementRecord(String pkg, IdleAction action, long ts, int count) {
            this.packageName = pkg;
            this.actionTaken = action;
            this.timestampMs = ts;
            this.killCount = count;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("package", packageName);
            o.put("action", actionTaken.name());
            o.put("ts", timestampMs);
            o.put("kill_count", killCount);
            return o;
        }

        public static AppEnforcementRecord fromJson(JSONObject o) throws Exception {
            return new AppEnforcementRecord(
                    o.getString("package"),
                    IdleAction.valueOf(o.optString("action",
                            IdleAction.STANDBY_BUCKET_RARE.name())),
                    o.optLong("ts", 0L),
                    o.optInt("kill_count", 0)
            );
        }
    }

    private static final class AppIdleState {
        int currentBucket = STANDBY_BUCKET_ACTIVE;
        int originalBucket = STANDBY_BUCKET_ACTIVE;
        boolean isRestricted = false;
        long restrictedAtMs = 0L;
    }

    private static volatile LunarisIdleManager sInstance;
    private static final Object sLock = new Object();

    private final Context mContext;
    private final Handler mMainHandler;
    private final ActivityManager mActivityManager;
    private final AlarmManager mAlarmManager;
    private final AudioManager mAudioManager;
    private final UsageStatsManager mUsageStatsManager;
    private final PowerManager mPowerManager;
    private final TelephonyManager mTelephonyManager;
    private final Executor mIoExecutor;

    private volatile boolean mEnabled = true;
    private volatile long mIdleTimeoutMs = TimeUnit.MINUTES.toMillis(60);
    private volatile boolean mDestroyed = false;
    private volatile Map<String, AppConfig> mAppConfigCache = Collections.emptyMap();

    private final Map<String, AppIdleState> mAppIdleStates = new ConcurrentHashMap<>();
    private final Map<String, Long> mLastKillTime = new ConcurrentHashMap<>();

    private BroadcastReceiver mAlarmReceiver;
    private ContentObserver mSettingsObserver;
    private UidObserver mUidObserver;
    private volatile boolean mIsRunning = false;

    private PowerManager.WakeLock mScanWakeLock;

    private LunarisIdleManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mUsageStatsManager = (UsageStatsManager) mContext.getSystemService(Context.USAGE_STATS_SERVICE);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

        mIoExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LunarisIdleManager-IO");
            t.setDaemon(true);
            return t;
        });

        loadBucketStates();
        loadConfigFromSettings();
        if (!mEnabled && !mAppIdleStates.isEmpty()) restoreAllBuckets();
        registerUidObserver();
        reconcileFullKillPackages();
        registerSettingsObserver();
        registerAlarmReceiver();
        initScanWakeLock();
    }

    public static void initManager(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new LunarisIdleManager(context);
                }
            }
        }
    }

    @Nullable
    public static LunarisIdleManager getInstance() { return sInstance; }

    public void executeManager() {
        if (mDestroyed) {
            Log.w(TAG, "executeManager called on destroyed instance");
            return;
        }
        if (!mEnabled) {
            Log.d(TAG, "LunarisIdleManager disabled — skipping");
            return;
        }
        if (mIsRunning) {
            Log.d(TAG, "Already running — ignoring duplicate start");
            return;
        }
        mIsRunning = true;
        mCycleGeneration++;
        mScansCompleted = 0;
        mCycleStartedElapsedMs = SystemClock.elapsedRealtime();
        cancelCallbacks();

        Log.d(TAG, "executeManager: appCount=" + mAppConfigCache.size()
                + " enabled=" + mEnabled);

        Log.d(TAG, "First scan in 30 sec; final scan after "
                + TimeUnit.MILLISECONDS.toMinutes(mIdleTimeoutMs) + " min");
        scheduleScanAlarm(INITIAL_SCAN_DELAY_MS);
    }

    public void haltManager() {
        if (mDestroyed) return;
        Log.d(TAG, "Halting LunarisIdleManager");
        cancelCallbacks();
        mIsRunning = false;
        mCycleGeneration++;
        restoreAllBuckets();
    }

    public void cleanup() {
        haltManager();
        mDestroyed = true;
        unregisterSettingsObserver();
        unregisterUidObserver();
        unregisterAlarmReceiver();
        releaseWakeLockIfHeld();
        synchronized (sLock) { sInstance = null; }
        Log.d(TAG, "LunarisIdleManager cleaned up");
    }

    public boolean isEnabled() {
        if (mDestroyed) {
            return false;
        }
        return mEnabled;
    }

    public boolean isRunning() {
        if (mDestroyed) {
            return false;
        }
        return mIsRunning;
    }

    public Map<String, AppConfig> getAppConfigs() {
        if (mDestroyed) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(mAppConfigCache);
    }

    public void setEnabled(boolean enabled) {
        if (mDestroyed) return;
        mEnabled = enabled;
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.IDLE_MANAGER, enabled ? 1 : 0);
        if (!enabled) {
            haltManager();
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 1);
            restoreAllBuckets();
        } else if (mPowerManager != null && !mPowerManager.isInteractive()) {
            executeManager();
        }
    }

    public void saveAppConfigs(@NonNull Map<String, AppConfig> configs) {
        if (mDestroyed) return;
        mAppConfigCache = new HashMap<>(configs);
        persistAppConfigs(configs);
    }

    public void addOrUpdateApp(@NonNull AppConfig config) {
        if (mDestroyed) return;
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.put(config.packageName, config);
        saveAppConfigs(updated);
    }

    public void removeApp(@NonNull String packageName) {
        if (mDestroyed) return;
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.remove(packageName);
        saveAppConfigs(updated);
        restoreBucket(packageName);
        mAppIdleStates.remove(packageName);
        mLastKillTime.remove(packageName);
    }

    @NonNull
    public List<AppEnforcementRecord> getEnforcementRecords() {
        if (mDestroyed) return Collections.emptyList();
        List<AppEnforcementRecord> records = new ArrayList<>();
        String json = Settings.Secure.getString(
                mContext.getContentResolver(), Settings.Secure.IDLE_MANAGER_KILL_STATS);
        if (json == null || json.isEmpty()) return records;
        try {
            JSONObject root = new JSONObject(json);
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject entry = root.optJSONObject(pkg);
                if (entry == null) continue;
                records.add(new AppEnforcementRecord(
                        pkg,
                        parseAction(entry.optString("last_action",
                                IdleAction.STANDBY_BUCKET_RARE.name())),
                        entry.optLong("last_kill", 0L),
                        entry.optInt("count", 0)
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse enforcement records", e);
        }
        records.sort((a, b) -> Long.compare(b.timestampMs, a.timestampMs));
        return records;
    }

    private static IdleAction parseAction(String s) {
        try { return IdleAction.valueOf(s); }
        catch (Exception e) { return IdleAction.STANDBY_BUCKET_RARE; }
    }

    private void initScanWakeLock() {
        if (mPowerManager != null) {
            mScanWakeLock = mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, TAG + ":ScanLock");
            mScanWakeLock.setReferenceCounted(false);
        }
    }

    private void acquireWakeLock() {
        if (mScanWakeLock != null && !mScanWakeLock.isHeld()) {
            mScanWakeLock.acquire(TimeUnit.MINUTES.toMillis(3));
        }
    }

    private void releaseWakeLockIfHeld() {
        if (mScanWakeLock != null && mScanWakeLock.isHeld()) {
            mScanWakeLock.release();
        }
    }

    private void scheduleScanAlarm(long delayMs) {
        if (mAlarmManager == null) {
            Log.e(TAG, "Cannot schedule scan: AlarmManager unavailable");
            return;
        }
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_SCAN_REQUEST,
                new Intent(ACTION_SCAN).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(delayMs, MIN_DELAY_MS);
        mAlarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        Log.d(TAG, "Scan alarm set in " + TimeUnit.MILLISECONDS.toSeconds(delayMs) + " sec");
    }

    private void cancelScanAlarm() {
        if (mAlarmManager == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_SCAN_REQUEST,
                new Intent(ACTION_SCAN).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) mAlarmManager.cancel(pi);
    }

    private void registerAlarmReceiver() {
        mAlarmReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (ACTION_SCAN.equals(action)) {
                    Log.d(TAG, "Scan alarm delivered");
                    onScanAlarmFired();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SCAN);
        mContext.registerReceiver(mAlarmReceiver, filter, SYSTEMUI_PERMISSION,
                null, Context.RECEIVER_EXPORTED_UNAUDITED);
    }

    private void unregisterAlarmReceiver() {
        if (mAlarmReceiver != null) {
            try { mContext.unregisterReceiver(mAlarmReceiver); }
            catch (IllegalArgumentException ignored) {}
            mAlarmReceiver = null;
        }
    }

    private void onScanAlarmFired() {
        if (!mIsRunning || mDestroyed) {
            Log.d(TAG, "Scan alarm ignored: running=" + mIsRunning
                    + " destroyed=" + mDestroyed);
            return;
        }
        int cycle = mCycleGeneration;
        acquireWakeLock();
        mIoExecutor.execute(() -> {
            try {
                if (!mIsRunning || cycle != mCycleGeneration) return;
                performIdleScan();
                if (!mIsRunning || cycle != mCycleGeneration) return;
                if (++mScansCompleted == 1) {
                    long elapsed = SystemClock.elapsedRealtime() - mCycleStartedElapsedMs;
                    long remaining = mIdleTimeoutMs + FINAL_SCAN_MARGIN_MS - elapsed;
                    scheduleScanAlarm(remaining);
                } else {
                    mIsRunning = false;
                    Log.d(TAG, "Final scan complete; waiting for next screen lock");
                }
            } finally {
                releaseWakeLockIfHeld();
            }
        });
    }

    private void performIdleScan() {
        if (mActivityManager == null || mUsageStatsManager == null) return;

        boolean screenOff = mPowerManager == null || !mPowerManager.isInteractive();

        if (!screenOff) {
            Log.d(TAG, "Screen is on — skipping scan");
            return;
        }

        Log.d(TAG, "performIdleScan: trigger=[screenOff]"
                + " evaluating " + mAppConfigCache.size() + " configured apps");

        List<ActivityManager.RunningAppProcessInfo> processes;
        try {
            processes = mActivityManager.getRunningAppProcesses();
        } catch (Exception e) {
            Log.e(TAG, "Error fetching processes", e);
            return;
        }
        if (processes == null) processes = Collections.emptyList();

        Set<String> foregroundPkgs = getForegroundPackages(processes);
        boolean audioActive = isAudioActive();
        long now = System.currentTimeMillis();
        int restricted = 0;
        int killed = 0;

        for (Map.Entry<String, AppConfig> entry : mAppConfigCache.entrySet()) {
            String pkg = entry.getKey();
            AppConfig cfg = entry.getValue();

            if (LunarisIdleConstants.PROTECTED_PACKAGES.contains(pkg)) {
                Log.w(TAG, "Skipping protected package: " + pkg);
                continue;
            }

            if (cfg.action != IdleAction.FULL_KILL && foregroundPkgs.contains(pkg)) {
                Log.d(TAG, "Skipping foreground: " + pkg);
                continue;
            }

            if (cfg.action != IdleAction.FULL_KILL
                    && audioActive && isActiveMediaApp(pkg, processes)) {
                Log.d(TAG, "Skipping active media: " + pkg);
                continue;
            }

            // At the final screen-off scan, the lock timeout has elapsed. Background
            // activity must not extend the grace period for a Full Kill target.
            boolean finalFullKill = cfg.action == IdleAction.FULL_KILL
                    && mScansCompleted > 0;
            if (!finalFullKill && !isAppIdleLongEnough(pkg, now)) {
                Log.d(TAG, "Not idle long enough: " + pkg);
                continue;
            }

            boolean didAct = false;

            switch (cfg.action) {
                case STANDBY_BUCKET_RARE:
                    didAct = applyStandbyBucket(pkg, STANDBY_BUCKET_RARE);
                    if (didAct) restricted++;
                    break;
                case STANDBY_BUCKET_RESTRICTED:
                    didAct = applyStandbyBucket(pkg, STANDBY_BUCKET_RESTRICTED);
                    if (didAct) restricted++;
                    break;
                case KILL_BACKGROUND:
                    didAct = killBackground(pkg, now);
                    if (didAct) killed++;
                    break;
                case FULL_KILL:
                    boolean r = applyStandbyBucket(pkg, STANDBY_BUCKET_RESTRICTED);
                    boolean k = forceStop(pkg, now);
                    didAct = r || k;
                    if (r) restricted++;
                    if (k) killed++;
                    break;
            }

            if (didAct) {
                updateKillStats(pkg, now, cfg.action);
            }
        }

        Log.i(TAG, "Scan done — restricted=" + restricted
                + " killed=" + killed
                + " total=" + mAppConfigCache.size());
    }

    private boolean applyStandbyBucket(String pkg, int targetBucket) {
        if (mUsageStatsManager == null) return false;

        AppIdleState state = mAppIdleStates.computeIfAbsent(pkg, k -> new AppIdleState());
        try {
            int actualBucket = mUsageStatsManager.getAppStandbyBucket(pkg);
            if (state.isRestricted && actualBucket != state.currentBucket) {
                // Another component changed this app's bucket; leave its decision alone.
                mAppIdleStates.remove(pkg);
                state = mAppIdleStates.computeIfAbsent(pkg, k -> new AppIdleState());
            }
            if (actualBucket >= targetBucket) return false;
            if (!state.isRestricted) state.originalBucket = actualBucket;
            mUsageStatsManager.setAppStandbyBucket(pkg, targetBucket);
            state.isRestricted = true;
            state.currentBucket = targetBucket;
            state.restrictedAtMs = System.currentTimeMillis();
            persistBucketStates();
            Log.d(TAG, "Bucket applied [" + bucketName(targetBucket) + "]: " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to set standby bucket for " + pkg + ": " + e.getMessage());
            return false;
        }
    }

    private boolean killBackground(String pkg, long now) {
        Long lastKill = mLastKillTime.get(pkg);
        if (lastKill != null && (now - lastKill) < mIdleTimeoutMs) {
            return false;
        }
        try {
            mActivityManager.killBackgroundProcesses(pkg);
            mLastKillTime.put(pkg, now);
            Log.d(TAG, "Killed background: " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to kill " + pkg + ": " + e.getMessage());
            return false;
        }
    }

    private boolean forceStop(String pkg, long now) {
        // The user may have reopened the app after an earlier stop. A previous
        // enforcement must not prevent stopping this new session.
        return forceStopPackageNow(pkg, now);
    }

    private boolean forceStopPackageNow(String pkg, long now) {
        try {
            mActivityManager.forceStopPackage(pkg);
            mLastKillTime.put(pkg, now);
            Log.i(TAG, "Force stopped: " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to force stop " + pkg, e);
            return false;
        }
    }

    private void registerUidObserver() {
        mUidObserver = new UidObserver() {
            @Override
            public void onUidGone(int uid, boolean disabled) {
                if (UserHandle.getUserId(uid) != mContext.getUserId()) return;
                mIoExecutor.execute(() -> handleUidGone(uid));
            }
        };
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, mContext.getOpPackageName());
        } catch (RemoteException | SecurityException e) {
            Log.e(TAG, "Unable to observe app process exits", e);
            mUidObserver = null;
        }
    }

    private void unregisterUidObserver() {
        if (mUidObserver == null) return;
        try {
            ActivityManager.getService().unregisterUidObserver(mUidObserver);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to unregister app process observer", e);
        }
        mUidObserver = null;
    }

    private void handleUidGone(int uid) {
        if (mDestroyed || !mEnabled) return;
        PackageManager pm = mContext.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) return;
        for (String pkg : packages) {
            if (isFullKillTarget(pkg) && !isPackageStopped(pm, pkg)
                    && forceStopPackageNow(pkg, System.currentTimeMillis())) {
                updateKillStats(pkg, System.currentTimeMillis(), IdleAction.FULL_KILL);
            }
        }
    }

    private boolean isFullKillTarget(String pkg) {
        AppConfig config = mAppConfigCache.get(pkg);
        return config != null && config.action == IdleAction.FULL_KILL
                && !LunarisIdleConstants.PROTECTED_PACKAGES.contains(pkg);
    }

    private boolean isPackageStopped(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return info.isStopped();
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private void reconcileFullKillPackages() {
        mIoExecutor.execute(() -> {
            if (mDestroyed || !mEnabled || mActivityManager == null) return;
            List<ActivityManager.RunningAppProcessInfo> processes =
                    mActivityManager.getRunningAppProcesses();
            if (processes == null) return;
            Set<String> running = new HashSet<>();
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pkgList != null) Collections.addAll(running, process.pkgList);
            }
            PackageManager pm = mContext.getPackageManager();
            for (String pkg : mAppConfigCache.keySet()) {
                if (isFullKillTarget(pkg) && !running.contains(pkg)
                        && !isPackageStopped(pm, pkg)
                        && forceStopPackageNow(pkg, System.currentTimeMillis())) {
                    updateKillStats(pkg, System.currentTimeMillis(), IdleAction.FULL_KILL);
                }
            }
        });
    }

    private void restoreBucket(String pkg) {
        AppIdleState state = mAppIdleStates.get(pkg);
        if (state == null || !state.isRestricted || mUsageStatsManager == null) return;
        try {
            if (mUsageStatsManager.getAppStandbyBucket(pkg) == state.currentBucket) {
                mUsageStatsManager.setAppStandbyBucket(pkg, state.originalBucket);
            }
            state.isRestricted  = false;
            mAppIdleStates.remove(pkg);
            persistBucketStates();
            Log.d(TAG, "Bucket restored: " + pkg);
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore bucket for " + pkg);
        }
    }

    private void restoreAllBuckets() {
        mIoExecutor.execute(() -> {
            Log.d(TAG, "restoreAllBuckets: starting for "
                    + mAppIdleStates.size() + " apps");
            for (String pkg : new HashSet<>(mAppIdleStates.keySet())) {
                restoreBucket(pkg);
            }
            Settings.Secure.putInt(
                    mContext.getContentResolver(),
                    Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 0);
            Log.d(TAG, "restoreAllBuckets: complete");
        });
    }

    private synchronized void persistBucketStates() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, AppIdleState> entry : mAppIdleStates.entrySet()) {
                AppIdleState state = entry.getValue();
                if (!state.isRestricted) continue;
                JSONObject buckets = new JSONObject();
                buckets.put("original", state.originalBucket);
                buckets.put("applied", state.currentBucket);
                root.put(entry.getKey(), buckets);
            }
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.IDLE_MANAGER_BUCKET_STATES, root.toString());
        } catch (Exception e) {
            Log.w(TAG, "Failed to save standby bucket states", e);
        }
    }

    private void loadBucketStates() {
        String json = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.IDLE_MANAGER_BUCKET_STATES);
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject buckets = root.getJSONObject(pkg);
                AppIdleState state = new AppIdleState();
                state.originalBucket = buckets.getInt("original");
                state.currentBucket = buckets.getInt("applied");
                state.isRestricted = true;
                mAppIdleStates.put(pkg, state);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read standby bucket states", e);
        }
    }

    private static String bucketName(int bucket) {
        switch (bucket) {
            case STANDBY_BUCKET_ACTIVE:
                return "ACTIVE";
            case STANDBY_BUCKET_WORKING_SET:
                return "WORKING_SET";
            case STANDBY_BUCKET_FREQUENT:
                return "FREQUENT";
            case STANDBY_BUCKET_RARE:
                return "RARE";
            case STANDBY_BUCKET_RESTRICTED:
                return "RESTRICTED";
            default:
                return "UNKNOWN(" + bucket + ")";
        }
    }

    private boolean isAppIdleLongEnough(String pkg, long now) {
        try {
            long begin = now - mIdleTimeoutMs;
            Map<String, UsageStats> stats =
                    mUsageStatsManager.queryAndAggregateUsageStats(begin, now);

            if (stats == null || stats.isEmpty()) {
                Log.w(TAG, "UsageStats returned empty — "
                        + "check PACKAGE_USAGE_STATS grant. Treating " + pkg + " as idle.");
                return true;
            }

            UsageStats appStats = stats.get(pkg);
            if (appStats == null) {
                Log.v(TAG, pkg + ": no usage in window → idle");
                return true;
            }

            long lastUsed = appStats.getLastTimeUsed();
            long idleDuration = now - lastUsed;

            Log.v(TAG, pkg + ": idle for "
                    + TimeUnit.MILLISECONDS.toMinutes(idleDuration)
                    + " min (threshold="
                    + TimeUnit.MILLISECONDS.toMinutes(mIdleTimeoutMs) + " min)");

            return idleDuration >= mIdleTimeoutMs;

        } catch (Exception e) {
            Log.w(TAG, "UsageStats query failed for " + pkg + ": " + e.getMessage());
            Long lastKill = mLastKillTime.get(pkg);
            return lastKill == null || (now - lastKill) >= mIdleTimeoutMs;
        }
    }

    private Set<String> getForegroundPackages(
            @NonNull List<ActivityManager.RunningAppProcessInfo> processes) {
        Set<String> fg = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.importance
                    <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                    && p.pkgList != null) {
                for (String pkg : p.pkgList) fg.add(pkg);
            }
        }
        return fg;
    }

    private boolean isAudioActive() {
        if (mAudioManager == null) return false;
        try {
            if (mAudioManager.isMusicActive()) return true;

            int mode = mAudioManager.getMode();
            if (mode == AudioManager.MODE_IN_CALL
                    || mode == AudioManager.MODE_IN_COMMUNICATION
                    || mode == AudioManager.MODE_RINGTONE) {
                Log.d(TAG, "Audio mode active (" + mode + ") — skipping enforcement");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "isAudioActive audio check failed: " + e.getMessage());
        }

        if (mTelephonyManager != null) {
            try {
                int callState = mTelephonyManager.getCallState();
                if (callState != TelephonyManager.CALL_STATE_IDLE) {
                    Log.d(TAG, "Call state active (" + callState + ") — skipping enforcement");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "TelephonyManager.getCallState() unavailable: " + e.getMessage());
            }
        }

        return false;
    }

    private boolean isActiveMediaApp(@NonNull String pkg,
            @NonNull List<ActivityManager.RunningAppProcessInfo> processes) {
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.pkgList == null) continue;
            for (String name : p.pkgList) {
                if (name.equals(pkg)) {
                    return p.importance
                            <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
                }
            }
        }
        return false;
    }

    private void loadGlobalTimeout(ContentResolver cr, @Nullable String appsJson) {
        String policy = Settings.Secure.getString(cr, Settings.Secure.IDLE_MANAGER_POLICY);
        String legacyPolicy = null;
        int legacyMinutes = 30;
        if (policy == null && appsJson != null) {
            try {
                JSONArray apps = new JSONArray(appsJson);
                if (apps.length() > 0) {
                    JSONObject first = apps.getJSONObject(0);
                    legacyPolicy = first.optString("policy", "BALANCED");
                    legacyMinutes = first.optInt("timeout_minutes", 30);
                }
            } catch (Exception e) {
                Log.w(TAG, "Unable to migrate legacy app timeout", e);
            }
        }
        String effectivePolicy = policy == null
                ? (legacyPolicy == null ? "BALANCED" : legacyPolicy) : policy;
        String saved = Settings.Secure.getString(cr, Settings.Secure.IDLE_MANAGER_TIMEOUT);
        int savedMinutes;
        try {
            savedMinutes = saved == null ? legacyMinutes : Integer.parseInt(saved);
        } catch (NumberFormatException e) {
            savedMinutes = legacyMinutes;
        }
        int minutes;
        switch (effectivePolicy) {
            case "AGGRESSIVE":
                minutes = 15;
                break;
            case "CUSTOM":
                minutes = savedMinutes;
                break;
            default:
                minutes = 60;
                break;
        }
        minutes = Math.max(MIN_TIMEOUT_MINUTES, Math.min(MAX_TIMEOUT_MINUTES, minutes));
        if (!"CUSTOM".equals(policy) || saved == null || savedMinutes != minutes) {
            Settings.Secure.putInt(cr, Settings.Secure.IDLE_MANAGER_TIMEOUT, minutes);
            Settings.Secure.putString(cr, Settings.Secure.IDLE_MANAGER_POLICY, "CUSTOM");
        }
        mIdleTimeoutMs = TimeUnit.MINUTES.toMillis(minutes);
        Log.d(TAG, "Global timeout: minutes=" + minutes);
    }

    private void loadConfigFromSettings() {
        ContentResolver cr = mContext.getContentResolver();
        mEnabled = Settings.Secure.getInt(cr, Settings.Secure.IDLE_MANAGER, 1) == 1;

        int restorePending = Settings.Secure.getInt(
                cr, Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 0);
        if (restorePending == 1) {
            Log.w(TAG, "Previous restoreAllBuckets was interrupted — re-running");
            mIoExecutor.execute(() -> {
                for (String pkg : new HashSet<>(mAppIdleStates.keySet())) {
                    restoreBucket(pkg);
                }
                Settings.Secure.putInt(
                        cr, Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 0);
            });
        }

        String appsJson = Settings.Secure.getString(cr, Settings.Secure.IDLE_MANAGER_APPS);
        Log.d(TAG, "loadConfigFromSettings: json=" + appsJson);
        loadGlobalTimeout(cr, appsJson);
        Map<String, AppConfig> previous = mAppConfigCache;
        mAppConfigCache = parseAppConfigs(appsJson);
        for (String pkg : previous.keySet()) {
            if (!mAppConfigCache.containsKey(pkg)) restoreBucket(pkg);
        }
        Log.d(TAG, "Config loaded — enabled=" + mEnabled
                + ", apps=" + mAppConfigCache.size());
    }

    private void registerSettingsObserver() {
        ContentResolver cr = mContext.getContentResolver();
        mSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                Log.d(TAG, "Settings changed — refreshing config");
                boolean wasEnabled = mEnabled;
                long previousTimeoutMs = mIdleTimeoutMs;
                loadConfigFromSettings();
                if (mIsRunning && mScansCompleted == 1
                        && previousTimeoutMs != mIdleTimeoutMs) {
                    long elapsed = SystemClock.elapsedRealtime() - mCycleStartedElapsedMs;
                    scheduleScanAlarm(mIdleTimeoutMs + FINAL_SCAN_MARGIN_MS - elapsed);
                }
                if (wasEnabled && !mEnabled) {
                    haltManager();
                } else if (!wasEnabled && mEnabled && mPowerManager != null
                        && !mPowerManager.isInteractive()) {
                    executeManager();
                }
                if (mEnabled) reconcileFullKillPackages();
            }
        };
        for (String key : new String[]{
                Settings.Secure.IDLE_MANAGER,
                Settings.Secure.IDLE_MANAGER_APPS,
                Settings.Secure.IDLE_MANAGER_POLICY,
                Settings.Secure.IDLE_MANAGER_TIMEOUT}) {
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mSettingsObserver);
        }
    }

    private void unregisterSettingsObserver() {
        if (mSettingsObserver != null)
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
    }

    private void persistAppConfigs(@NonNull Map<String, AppConfig> configs) {
        mIoExecutor.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (AppConfig c : configs.values()) arr.put(c.toJson());
                Settings.Secure.putString(
                        mContext.getContentResolver(),
                        Settings.Secure.IDLE_MANAGER_APPS, arr.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to persist configs", e);
            }
        });
    }

    @NonNull
    private Map<String, AppConfig> parseAppConfigs(@Nullable String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        Map<String, AppConfig> map = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                AppConfig c = AppConfig.fromJson(arr.getJSONObject(i));
                map.put(c.packageName, c);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse configs: " + e.getMessage());
        }
        return map;
    }

    private void updateKillStats(@NonNull String pkg, long tsMs, @NonNull IdleAction action) {
        mIoExecutor.execute(() -> {
            ContentResolver cr = mContext.getContentResolver();
            try {
                String existing = Settings.Secure.getString(
                        cr, Settings.Secure.IDLE_MANAGER_KILL_STATS);
                JSONObject root = (existing != null && !existing.isEmpty())
                        ? new JSONObject(existing) : new JSONObject();

                JSONObject entry = root.optJSONObject(pkg);
                if (entry == null) entry = new JSONObject();

                entry.put("count", entry.optInt("count", 0) + 1);
                entry.put("last_kill", tsMs);
                entry.put("last_action", action.name());
                root.put(pkg, entry);

                Settings.Secure.putString(
                        cr, Settings.Secure.IDLE_MANAGER_KILL_STATS, root.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to update stats for " + pkg, e);
            }
        });
    }

    private void cancelCallbacks() {
        cancelScanAlarm();
    }
}
