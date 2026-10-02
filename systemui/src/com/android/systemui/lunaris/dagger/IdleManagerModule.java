/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lunaris.dagger;

import com.android.systemui.CoreStartable;
import com.android.systemui.lunaris.IdleManagerStartable;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

@Module
public abstract class IdleManagerModule {
    @Binds
    @IntoMap
    @ClassKey(IdleManagerStartable.class)
    public abstract CoreStartable bindIdleManagerStartable(IdleManagerStartable impl);
}
