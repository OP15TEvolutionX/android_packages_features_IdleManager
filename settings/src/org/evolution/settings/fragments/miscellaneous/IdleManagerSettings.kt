/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal enum class IdleAction {
    STANDBY_BUCKET_RARE,
    STANDBY_BUCKET_RESTRICTED,
    KILL_BACKGROUND,
    FULL_KILL;

    val icon: ImageVector
        get() = when (this) {
            STANDBY_BUCKET_RARE -> Icons.Default.Timer
            STANDBY_BUCKET_RESTRICTED -> Icons.Default.Block
            KILL_BACKGROUND -> Icons.Default.Stop
            FULL_KILL -> Icons.Default.FlashOn
        }

    companion object {
        fun fromString(v: String) = entries.firstOrNull {
            it.name == v
            } ?: STANDBY_BUCKET_RARE
    }
}

private val CRITICAL_SYSTEM_PACKAGES = setOf(
    "android",
    "com.android.systemui",
    "com.android.phone",
    "com.android.providers.telephony",
    "com.android.server.telecom",
    "com.google.android.apps.messaging",
    "com.google.android.dialer",
    "com.whatsapp",
    "org.evolution.dolby"
)

private data class IdleAppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean
)

private data class IdleAppConfig(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val action: IdleAction
)

private data class IdleGlobalConfig(
    val timeoutMinutes: Int
)

private data class EnforcementRecord(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val actionTaken: IdleAction,
    val lastKillMs: Long,
    val killCount: Int
)

private fun readEnabled(ctx: Context) =
    Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.IDLE_MANAGER, 1) == 1

private fun writeEnabled(ctx: Context, v: Boolean) =
    Settings.Secure.putInt(
        ctx.contentResolver,
        Settings.Secure.IDLE_MANAGER,
        if (v) 1 else 0
    )

private fun readGlobalConfig(ctx: Context): IdleGlobalConfig {
    val cr = ctx.contentResolver
    val savedPolicy = Settings.Secure.getString(cr, Settings.Secure.IDLE_MANAGER_POLICY)
    var legacyPolicy = "BALANCED"
    var legacyMinutes = 30
    if (savedPolicy == null) {
        runCatching {
            val apps = JSONArray(
                Settings.Secure.getString(cr, Settings.Secure.IDLE_MANAGER_APPS) ?: "[]"
            )
            if (apps.length() > 0) {
                val first = apps.getJSONObject(0)
                legacyPolicy = first.optString("policy", "BALANCED")
                legacyMinutes = first.optInt("timeout_minutes", 30)
            }
        }
    }
    val policy = savedPolicy ?: legacyPolicy
    val savedMinutes = Settings.Secure.getInt(
        cr, Settings.Secure.IDLE_MANAGER_TIMEOUT, legacyMinutes
    )
    val minutes = when (policy) {
        "BALANCED" -> 60
        "AGGRESSIVE" -> 15
        else -> savedMinutes
    }.coerceIn(1, 240)
    if (savedPolicy != "CUSTOM") {
        Settings.Secure.putInt(cr, Settings.Secure.IDLE_MANAGER_TIMEOUT, minutes)
        Settings.Secure.putString(cr, Settings.Secure.IDLE_MANAGER_POLICY, "CUSTOM")
    }
    return IdleGlobalConfig(minutes)
}

private fun readAppConfigs(ctx: Context): LinkedHashMap<String, IdleAppConfig> {
    val result = linkedMapOf<String, IdleAppConfig>()
    val json = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.IDLE_MANAGER_APPS
    ) ?: return result
    if (json.isBlank()) return result
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val pkg = obj.getString("package")
            val act = IdleAction.fromString(
                obj.optString("action", IdleAction.STANDBY_BUCKET_RARE.name)
            )
            result[pkg] = IdleAppConfig(
                packageName = pkg,
                label = pkg,
                icon = null,
                isSystem = false,
                action = act
            )
        }
    } catch (_: Exception) {}
    return result
}

private fun writeAppConfigs(ctx: Context, configs: Map<String, IdleAppConfig>) {
    val arr = JSONArray()
    configs.values.forEach { c ->
        arr.put(JSONObject().apply {
            put("package", c.packageName)
            put("action", c.action.name)
        })
    }
    Settings.Secure.putString(
        ctx.contentResolver,
        Settings.Secure.IDLE_MANAGER_APPS,
        arr.toString()
    )
}

private fun readEnforcementRecords(
    ctx: Context,
    apps: List<IdleAppItem>
): List<EnforcementRecord> {
    val json = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.IDLE_MANAGER_KILL_STATS
    ) ?: return emptyList()
    if (json.isBlank()) return emptyList()
    val appMap = apps.associateBy { it.packageName }
    val records = mutableListOf<EnforcementRecord>()
    try {
        val root = JSONObject(json)
        root.keys().forEach { pkg ->
            val entry = root.optJSONObject(pkg) ?: return@forEach
            val app = appMap[pkg]
            records.add(
                EnforcementRecord(
                    packageName = pkg,
                    label = app?.label ?: pkg,
                    icon = app?.icon,
                    actionTaken = IdleAction.fromString(
                        entry.optString("last_action", IdleAction.STANDBY_BUCKET_RARE.name)
                    ),
                    lastKillMs = entry.optLong("last_kill", 0L),
                    killCount = entry.optInt("count", 0)
                )
            )
        }
    } catch (_: Exception) {}
    return records.sortedByDescending { it.lastKillMs }
}

@Composable
private fun formatElapsed(ms: Long): String {
    if (ms == 0L) return stringResource(R.string.idle_manager_elapsed_never)
    val elapsed = System.currentTimeMillis() - ms
    val mins = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hrs = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        mins < 1 -> stringResource(R.string.idle_manager_elapsed_now)
        mins < 60 -> stringResource(R.string.idle_manager_elapsed_minutes, mins)
        hrs < 24 -> stringResource(R.string.idle_manager_elapsed_hours, hrs)
        else -> stringResource(R.string.idle_manager_elapsed_days, days)
    }
}

class IdleManagerSettings : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.idle_manager_title)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(
            ViewCompositionStrategy
                .DisposeOnViewTreeLifecycleDestroyed
        )
        setContent {
            SettingsTheme {
                IdleManagerRoot(requireContext())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IdleManagerRoot(ctx: Context) {
    val pm = ctx.packageManager
    val scope = rememberCoroutineScope()

    var allApps by remember { mutableStateOf(listOf<IdleAppItem>()) }
    var configuredApps by remember { mutableStateOf(linkedMapOf<String, IdleAppConfig>()) }
    var globalEnabled by remember { mutableStateOf(true) }
    var globalTimeoutMinutes by remember { mutableIntStateOf(60) }
    var records by remember { mutableStateOf(listOf<EnforcementRecord>()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<IdleAppConfig?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun mergeWithAppInfo(
        raw: LinkedHashMap<String, IdleAppConfig>,
        apps: List<IdleAppItem>
    ): LinkedHashMap<String, IdleAppConfig> {
        val out = linkedMapOf<String, IdleAppConfig>()
        raw.forEach { (pkg, cfg) ->
            val app = apps.find { it.packageName == pkg } ?: return@forEach
            out[pkg] = cfg.copy(label = app.label, icon = app.icon, isSystem = app.isSystem)
        }
        return out
    }

    fun refreshRecords() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                readEnforcementRecords(ctx, allApps)
            }
            records = result
        }
    }

    fun loadAll() {
        scope.launch {
            val enabled = withContext(Dispatchers.IO) { readEnabled(ctx) }
            val globalConfig = withContext(Dispatchers.IO) { readGlobalConfig(ctx) }
            val raw = withContext(Dispatchers.IO) { readAppConfigs(ctx) }
            globalEnabled = enabled
            globalTimeoutMinutes = globalConfig.timeoutMinutes
            configuredApps = mergeWithAppInfo(raw, allApps)
            refreshRecords()
        }
    }

    fun persist(updated: LinkedHashMap<String, IdleAppConfig>) {
        configuredApps = updated
        scope.launch(Dispatchers.IO) {
            writeAppConfigs(ctx, updated)
        }
    }

    fun upsert(pkg: String, action: IdleAction, app: IdleAppItem) {
        persist(linkedMapOf<String, IdleAppConfig>().apply {
            putAll(configuredApps)
            put(pkg, IdleAppConfig(pkg, app.label, app.icon, app.isSystem, action))
        })
    }

    fun saveTimeoutMinutes(minutes: Int) {
        globalTimeoutMinutes = minutes
        scope.launch(Dispatchers.IO) {
            Settings.Secure.putInt(
                ctx.contentResolver, Settings.Secure.IDLE_MANAGER_TIMEOUT, minutes
            )
        }
    }

    fun remove(pkg: String) {
        persist(linkedMapOf<String, IdleAppConfig>().apply {
            putAll(configuredApps.filter { it.key != pkg })
        })
    }

    fun clearAll() = persist(linkedMapOf())

    fun clearStats() {
        scope.launch(Dispatchers.IO) {
            Settings.Secure.putString(
                ctx.contentResolver,
                Settings.Secure.IDLE_MANAGER_KILL_STATS,
                ""
            )
            withContext(Dispatchers.Main) {
                records = emptyList()
            }
        }
    }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) {
            val overlayPackages = pm.getInstalledPackages(0)
                .filter { it.overlayTarget != null }
                .map { it.packageName }
                .toSet()
            pm.getInstalledPackages(PackageManager.MATCH_ANY_USER)
                .mapNotNull { pkg ->
                    val ai = pkg.applicationInfo ?: return@mapNotNull null
                    if (pkg.packageName in overlayPackages) return@mapNotNull null
                    IdleAppItem(
                        packageName = pkg.packageName,
                        label = ai.loadLabel(pm).toString(),
                        icon = runCatching { ai.loadIcon(pm) }.getOrNull()
                            ?: return@mapNotNull null,
                        isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                   || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase(Locale.getDefault()) }
        }
        loadAll()
    }

    if (showAddDialog) {
        AddAppDialog(
            allApps = allApps,
            configuredPackages = configuredApps.keys,
            onDismiss = {
                showAddDialog = false
            },
            onAppsAdded = { apps, action ->
                persist(linkedMapOf<String, IdleAppConfig>().apply {
                    putAll(configuredApps)
                    apps.forEach { app ->
                        put(app.packageName, IdleAppConfig(
                            app.packageName, app.label, app.icon, app.isSystem, action
                        ))
                    }
                })
                showAddDialog = false
            }
        )
    }

    showEditDialog?.let { target ->
        EditAppDialog(
            config = target,
            onDismiss = {
                showEditDialog = null
            },
            onSave = { action ->
                upsert(
                    target.packageName, action,
                    IdleAppItem(target.packageName, target.label, target.icon, target.isSystem)
                )
                showEditDialog = null
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = {
                showClearConfirm = false
            },
            icon  = {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text(stringResource(R.string.idle_manager_clear_all))
            },
            text  = {
                Text(stringResource(R.string.idle_manager_clear_all_confirm))
            },
            confirmButton = {
                Button(
                    onClick = { clearAll(); showClearConfirm = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.idle_manager_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SpoofingHeaderCard(
                title = stringResource(
                    if (globalEnabled) R.string.idle_manager_enabled_status
                    else R.string.idle_manager_disabled_status
                ),
                subtitle = stringResource(
                    R.string.idle_manager_app_count, configuredApps.size
                ),
                checked = globalEnabled,
                onCheckedChange = { v ->
                    globalEnabled = v
                    scope.launch(Dispatchers.IO) { writeEnabled(ctx, v) }
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) {
                Icon(
                    Icons.Default.BatteryAlert,
                    contentDescription = null,
                    tint = if (globalEnabled) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }

            SpoofingAnimatedVisibility(visible = globalEnabled) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IdlePageSelector(
                            title = stringResource(R.string.idle_manager_tab_apps),
                            selected = selectedTab == 0,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedTab = 0 }
                        )
                        IdlePageSelector(
                            title = stringResource(R.string.idle_manager_tab_dashboard),
                            selected = selectedTab == 1,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                selectedTab = 1
                                refreshRecords()
                            }
                        )
                    }

                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                        },
                        label = "tab_content"
                    ) { tab ->
                        LaunchedEffect(tab) {
                            if (tab == 1) {
                                refreshRecords()
                            }
                        }
                        when (tab) {
                            0 -> AppsTab(
                                configuredApps = configuredApps,
                                timeoutMinutes = globalTimeoutMinutes,
                                onTimeoutChange = { saveTimeoutMinutes(it) },
                                onAdd = {
                                    showAddDialog = true
                                },
                                onClearAll = {
                                    showClearConfirm = true
                                },
                                onEdit = {
                                    showEditDialog = it
                                },
                                onRemove = {
                                    remove(it)
                                },
                                records = records
                            )
                            1 -> DashboardTab(
                                records = records,
                                onRefresh = {
                                    refreshRecords()
                                },
                                onClearStats = {
                                    clearStats()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsTab(
    configuredApps: LinkedHashMap<String, IdleAppConfig>,
    timeoutMinutes: Int,
    onTimeoutChange: (Int) -> Unit,
    onAdd: () -> Unit,
    onClearAll: () -> Unit,
    onEdit: (IdleAppConfig) -> Unit,
    onRemove: (String) -> Unit,
    records: List<EnforcementRecord>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        GlobalPolicyCard(
            timeoutMinutes = timeoutMinutes,
            onTimeoutChange = onTimeoutChange
        )

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.idle_manager_add_apps),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            OutlinedButton(
                onClick = onClearAll,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.idle_manager_clear_all),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (configuredApps.isEmpty()) {
            SpoofingEmptyState(
                icon = Icons.Default.PowerSettingsNew,
                title = stringResource(R.string.idle_manager_no_apps_configured),
                description = stringResource(R.string.idle_manager_no_apps_hint),
            )
        } else {
            SectionLabel(stringResource(R.string.idle_manager_configured_apps))
            configuredApps.values.forEach { cfg ->
                AppConfigCard(
                    config = cfg,
                    record = records.find {
                        it.packageName == cfg.packageName
                    },
                    onEdit = {
                        onEdit(cfg)
                    },
                    onRemove = {
                        onRemove(cfg.packageName)
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun DashboardTab(
    records: List<EnforcementRecord>,
    onRefresh: () -> Unit,
    onClearStats: () -> Unit
) {
    var showClearStatsConfirm by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(10_000L)
            onRefresh()
        }
    }

    if (showClearStatsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearStatsConfirm = false },
            icon = {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text(stringResource(R.string.idle_manager_clear_stats_title))
            },
            text = {
                Text(stringResource(R.string.idle_manager_clear_stats_confirm))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearStats()
                        showClearStatsConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.idle_manager_clear_stats_title))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearStatsConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                label = stringResource(R.string.idle_manager_apps_acted_on),
                value = records.size.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = stringResource(R.string.idle_manager_total_actions),
                value = records.sumOf {
                    it.killCount
                }.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        if (records.isNotEmpty()) {
            val byAction = IdleAction.entries.mapNotNull { action ->
                val count = records.count { it.actionTaken == action }
                if (count > 0) action to count else null
            }
            if (byAction.isNotEmpty()) {
                SectionLabel(stringResource(R.string.idle_manager_action_breakdown))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    byAction.forEach { (action, count) ->
                        ActionBadgeCard(action, count, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel(
                text = stringResource(R.string.idle_manager_recent_activity),
                includeBottomPadding = false,
            )
            if (records.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showClearStatsConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 6.dp
                    )
                ) {
                    Icon(
                        Icons.Default.Delete, null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.idle_manager_clear_stats_short),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (records.isEmpty()) {
            SpoofingEmptyState(
                icon = Icons.Default.Shield,
                title = stringResource(R.string.idle_manager_no_activity),
                description = stringResource(R.string.idle_manager_no_activity_hint),
            )
        } else {
            records.forEach { rec ->
                EnforcementRecordCard(record = rec)
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.idle_manager_refresh_dashboard))
        }

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center
            )
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActionBadgeCard(action: IdleAction, count: Int, modifier: Modifier = Modifier) {
    val color = actionColor(action)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(action.icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                actionDisplayName(action),
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EnforcementRecordCard(record: EnforcementRecord) {
    val color = actionColor(record.actionTaken)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconOrPlaceholder(
                packageName = record.packageName,
                icon = record.icon,
                sizeDp = APP_ICON_SIZE_RECORD,
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    record.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    record.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.idle_manager_last_killed,
                        formatElapsed(record.lastKillMs)
                    ) + " · ×${record.killCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(record.actionTaken.icon, null,
                        tint = color,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        actionDisplayName(record.actionTaken),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppConfigCard(
    config: IdleAppConfig,
    record: EnforcementRecord?,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }

    var showRemoveAlert by remember {
        mutableStateOf(false)
    }

    val isCritical = config.isSystem && CRITICAL_SYSTEM_PACKAGES.contains(config.packageName)
    val actionColor = actionColor(config.action)

    if (showRemoveAlert) {
        AlertDialog(
            onDismissRequest = { showRemoveAlert = false },
            icon  = {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text(stringResource(R.string.idle_manager_remove_title))
            },
            text  = {
                Text(stringResource(R.string.idle_manager_remove_confirm, config.label))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveAlert = false
                        onRemove()
                    },
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRemoveAlert = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
            .combinedClickable(onClick = {
                expanded = !expanded
            },
            onLongClick = onEdit
            ),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
            )
    ) {
        Column(
            Modifier.padding(16.dp)) {

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    AppIconOrPlaceholder(
                        packageName = config.packageName,
                        icon = config.icon,
                        sizeDp = APP_ICON_SIZE_LARGE,
                    )
                    if (config.isSystem) {
                        SystemAppBadge(
                            isCritical = isCritical,
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                    record?.let {
                        if (it.killCount > 0) {
                            Badge(
                                Modifier.align(Alignment.TopEnd),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(if (it.killCount > 99) "99+" else it.killCount.toString())
                            }
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        config.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        config.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    record?.let {
                        if (it.killCount > 0) {
                            Text(
                                stringResource(
                                    R.string.idle_manager_last_killed,
                                    formatElapsed(it.lastKillMs)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(actionColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                config.action.icon, null,
                                tint = actionColor,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                actionDisplayName(config.action),
                                style = MaterialTheme.typography.labelSmall,
                                color = actionColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))

                if (isCritical) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.idle_manager_critical_system_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        DetailRow(
                            stringResource(R.string.idle_manager_enforcement_action),
                            actionDisplayName(config.action),
                            actionColor
                        )
                        record?.let {
                            if (it.killCount > 0) {
                                DetailRow(
                                    stringResource(R.string.idle_manager_kill_count_label),
                                    it.killCount.toString(),
                                    MaterialTheme.colorScheme.secondary
                                )
                                DetailRow(
                                    stringResource(R.string.idle_manager_last_killed_label),
                                    formatElapsed(it.lastKillMs),
                                    MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.idle_manager_longpress_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        showRemoveAlert = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        Icons.Default.Delete, null, Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.remove))
                }
                FilledTonalButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Edit, null, Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.edit))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppDialog(
    allApps: List<IdleAppItem>,
    configuredPackages: Set<String>,
    onDismiss: () -> Unit,
    onAppsAdded: (List<IdleAppItem>, IdleAction) -> Unit
) {
    var search by remember {
        mutableStateOf("")
    }
    var showSystem by remember {
        mutableStateOf(false)
    }
    var showMenu by remember {
        mutableStateOf(false)
    }
    var selectedApps by remember {
        mutableStateOf(setOf<IdleAppItem>())
    }
    var showActionStep by remember {
        mutableStateOf(false)
    }

    val filtered = allApps.filter { app ->
        if (configuredPackages.contains(app.packageName))
            return@filter false
        if (!showSystem && app.isSystem)
            return@filter false
        if (search.isBlank())
            return@filter true
        app.label.contains(search, true) || app.packageName.contains(search, true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showActionStep) {
                    TextButton(onClick = {
                        showActionStep = false
                    }) {
                        Text(stringResource(R.string.idle_manager_back_to_apps))
                    }
                    Text(
                        stringResource(R.string.idle_manager_enforcement_action),
                        style = MaterialTheme.typography.titleMedium
                    )
                } else {
                    Text(
                        stringResource(R.string.idle_manager_add_apps)
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, null)
                        }
                        DropdownMenu(showMenu, { showMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (showSystem) stringResource(R.string.hide_system_apps)
                                        else stringResource(R.string.show_system_apps)
                                    )
                                },
                                onClick = {
                                    showSystem = !showSystem
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().height(500.dp)) {
                if (!showActionStep) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.action_search_apps)) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())) {
                        if (filtered.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (search.isBlank())
                                        stringResource(R.string.idle_manager_no_apps_available)
                                    else
                                        stringResource(
                                            R.string.idle_manager_no_apps_found, search),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            filtered.forEach { app ->
                                AppSelectRow(
                                    app = app,
                                    selected = selectedApps.any {
                                        it.packageName == app.packageName },
                                    onClick = {
                                        selectedApps =
                                            if (selectedApps.any {
                                                    it.packageName == app.packageName })
                                                selectedApps.filter {
                                                    it.packageName != app.packageName }.toSet()
                                            else
                                                selectedApps + app
                                    }
                                )
                            }
                        }
                    }
                    if (selectedApps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showActionStep = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    R.string.idle_manager_select_action_count,
                                    selectedApps.size
                                )
                            )
                        }
                    }
                } else {
                    ActionSelector(
                        modifier = Modifier.weight(1f),
                        onConfirm = { action ->
                            onAppsAdded(selectedApps.toList(), action)
                        }
                    )
                }
            }
        },
        confirmButton = {
            if (!showActionStep) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        dismissButton = null
    )
}

@Composable
private fun AppSelectRow(app: IdleAppItem, selected: Boolean, onClick: () -> Unit) {
    val isCritical = app.isSystem && CRITICAL_SYSTEM_PACKAGES.contains(app.packageName)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else
                    Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AppIconOrPlaceholder(
                packageName = app.packageName,
                icon = app.icon,
                sizeDp = APP_ICON_SIZE_LIST,
            )
            if (app.isSystem) {
                SystemAppBadge(
                    isCritical = isCritical,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isCritical) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Warning, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(
                Icons.Default.Check, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EditAppDialog(
    config: IdleAppConfig,
    onDismiss: () -> Unit,
    onSave: (IdleAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(
                R.string.idle_manager_edit_action,
                config.label)
            )
        },
        text = {
            ActionSelector(
                initialAction = config.action,
                onConfirm = onSave
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun IdlePageSelector(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(48.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                title,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GlobalPolicyCard(
    timeoutMinutes: Int,
    onTimeoutChange: (Int) -> Unit
) {
    var sliderMinutes by remember(timeoutMinutes) {
        mutableFloatStateOf(timeoutMinutes.toFloat().coerceIn(1f, 240f))
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.idle_manager_idle_timeout),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.idle_manager_timeout_label),
                style = MaterialTheme.typography.bodyMedium
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    stringResource(R.string.idle_manager_timeout_minutes, sliderMinutes.roundToInt()),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Slider(
            value = sliderMinutes,
            onValueChange = { sliderMinutes = it },
            onValueChangeFinished = { onTimeoutChange(sliderMinutes.roundToInt()) },
            valueRange = 1f..240f,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.idle_manager_timeout_min),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.idle_manager_timeout_max),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = stringResource(
                    R.string.idle_manager_policy_custom_desc_dynamic,
                    sliderMinutes.roundToInt()
                ),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

    }
}

@Composable
private fun ActionSelector(
    modifier: Modifier = Modifier,
    initialAction: IdleAction = IdleAction.STANDBY_BUCKET_RARE,
    onConfirm: (IdleAction) -> Unit
) {
    var selectedAction by remember { mutableStateOf(initialAction) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IdleAction.entries.forEach { action ->
            ActionOptionCard(
                action = action,
                selected = selectedAction == action,
                onClick = { selectedAction = action }
            )
        }
        Button(
            onClick = { onConfirm(selectedAction) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_apply))
        }
    }
}

@Composable
private fun ActionOptionCard(action: IdleAction, selected: Boolean, onClick: () -> Unit) {
    val color = actionColor(action)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (selected)
                    color.copy(alpha = 0.12f)
                else
                    MaterialTheme.colorScheme.surfaceBright,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(
                        alpha = if (selected) 0.2f else 0.08f
                    )),
                contentAlignment = Alignment.Center
            ) {
                Icon(action.icon, null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    actionDisplayName(action),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) color else Color.Unspecified
                )
                Text(
                    actionDescription(action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check, null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun actionColor(action: IdleAction): Color = when (action) {
    IdleAction.STANDBY_BUCKET_RARE -> MaterialTheme.colorScheme.tertiary
    IdleAction.STANDBY_BUCKET_RESTRICTED -> MaterialTheme.colorScheme.secondary
    IdleAction.KILL_BACKGROUND -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
    IdleAction.FULL_KILL -> MaterialTheme.colorScheme.error
}

@Composable
private fun actionDisplayName(action: IdleAction): String = when (action) {
    IdleAction.STANDBY_BUCKET_RARE -> stringResource(R.string.idle_action_rare_bucket)
    IdleAction.STANDBY_BUCKET_RESTRICTED -> stringResource(R.string.idle_action_restricted)
    IdleAction.KILL_BACKGROUND -> stringResource(R.string.idle_action_kill_bg)
    IdleAction.FULL_KILL -> stringResource(R.string.idle_action_full_kill)
}

@Composable
private fun actionDescription(action: IdleAction): String = when (action) {
    IdleAction.STANDBY_BUCKET_RARE -> stringResource(R.string.idle_action_rare_bucket_desc)
    IdleAction.STANDBY_BUCKET_RESTRICTED -> stringResource(R.string.idle_action_restricted_desc)
    IdleAction.KILL_BACKGROUND -> stringResource(R.string.idle_action_kill_bg_desc)
    IdleAction.FULL_KILL -> stringResource(R.string.idle_action_full_kill_desc)
}
