/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lunaris;

import android.content.Context;
import android.os.PowerManager;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.keyguard.WakefulnessLifecycle;

import javax.inject.Inject;

/** Starts the idle manager with SystemUI and follows the device wakefulness lifecycle. */
@SysUISingleton
public final class IdleManagerStartable implements CoreStartable, WakefulnessLifecycle.Observer {
    private final Context mContext;
    private final WakefulnessLifecycle mWakefulnessLifecycle;

    @Inject
    public IdleManagerStartable(@Application Context context,
            WakefulnessLifecycle wakefulnessLifecycle) {
        mContext = context;
        mWakefulnessLifecycle = wakefulnessLifecycle;
    }

    @Override
    public void start() {
        LunarisIdleManager.initManager(mContext);
        mWakefulnessLifecycle.addObserver(this);

        PowerManager powerManager = mContext.getSystemService(PowerManager.class);
        if (powerManager != null && !powerManager.isInteractive()) {
            onStartedGoingToSleep();
        }
    }

    @Override
    public void onStartedGoingToSleep() {
        LunarisIdleManager manager = LunarisIdleManager.getInstance();
        if (manager != null) {
            manager.executeManager();
        }
    }

    @Override
    public void onStartedWakingUp() {
        LunarisIdleManager manager = LunarisIdleManager.getInstance();
        if (manager != null) {
            manager.haltManager();
        }
    }
}
