package com.aliceclais.realmep3litetweaker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TweakerScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweakerScreen() {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getWorldReadablePrefs(context) }
    val isModuleActive = prefs != null

    var enableAnims by remember {
        mutableStateOf(prefs?.getBoolean(PrefsManager.KEY_ENABLE_ANIMATIONS, true) ?: true)
    }
    var forceFloatingIcon by remember {
        mutableStateOf(prefs?.getBoolean(PrefsManager.KEY_FORCE_FLOATING_ICON, true) ?: true)
    }
    var hookSystemServer by remember {
        mutableStateOf(prefs?.getBoolean(PrefsManager.KEY_HOOK_SYSTEM_SERVER, false) ?: false)
    }
    var safeGuard by remember {
        mutableStateOf(prefs?.getBoolean(PrefsManager.KEY_SAFE_MODE, true) ?: true)
    }
    var animScale by remember {
        mutableFloatStateOf(prefs?.getFloat(PrefsManager.KEY_ANIM_SPEED_SCALE, 1.0f) ?: 1.0f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TweakTheP3Lite", fontWeight = FontWeight.Bold)
                        Text(
                            "Animation Unlocker • Safe Mode Ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isModuleActive)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (isModuleActive) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        contentDescription = null
                    )
                    Column {
                        Text(
                            text = if (isModuleActive) "LSPosed модуль активен" else "Модуль не активирован в LSPosed",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isModuleActive)
                                "Безопасный режим: сначала тестируется только лаунчер"
                            else
                                "Включи модуль в LSPosed и отметь Лаунчер (com.android.launcher3)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SwitchRow(
                        title = "Плавные анимации входа и выхода",
                        subtitle = "Разблокирует QuickstepTransitionManager в лаунчере",
                        icon = Icons.Rounded.Animation,
                        checked = enableAnims,
                        enabled = isModuleActive
                    ) {
                        enableAnims = it
                        prefs?.edit()?.putBoolean(PrefsManager.KEY_ENABLE_ANIMATIONS, it)?.apply()
                    }

                    HorizontalDivider()

                    SwitchRow(
                        title = "Морфинг окна в иконку (FloatingIconView)",
                        subtitle = "Плавное сворачивание приложения прямо в его ярлык",
                        icon = Icons.Rounded.Animation,
                        checked = forceFloatingIcon,
                        enabled = isModuleActive && enableAnims
                    ) {
                        forceFloatingIcon = it
                        prefs?.edit()?.putBoolean(PrefsManager.KEY_FORCE_FLOATING_ICON, it)?.apply()
                    }

                    HorizontalDivider()

                    SwitchRow(
                        title = "Анти-краш защита (SafeGuard)",
                        subtitle = "Автоотключение хуков при 3 сбоях лаунчера за 20 сек",
                        icon = Icons.Rounded.Shield,
                        checked = safeGuard,
                        enabled = isModuleActive
                    ) {
                        safeGuard = it
                        prefs?.edit()?.putBoolean(PrefsManager.KEY_SAFE_MODE, it)?.apply()
                    }

                    HorizontalDivider()

                    SwitchRow(
                        title = "Расширенный хук System Framework",
                        subtitle = "Патч WindowManager (включать только если не хватило хука лаунчера)",
                        icon = Icons.Rounded.Security,
                        checked = hookSystemServer,
                        enabled = isModuleActive && enableAnims
                    ) {
                        hookSystemServer = it
                        prefs?.edit()?.putBoolean(PrefsManager.KEY_HOOK_SYSTEM_SERVER, it)?.apply()
                    }
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val formatted = ((animScale * 100).roundToInt() / 100.0)
                    Text(
                        text = "Масштаб длительности анимации: ${formatted}x",
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = animScale,
                        onValueChange = { animScale = it },
                        onValueChangeFinished = {
                            prefs?.edit()?.putFloat(PrefsManager.KEY_ANIM_SPEED_SCALE, animScale)?.apply()
                        },
                        valueRange = 0.5f..1.5f,
                        steps = 9,
                        enabled = isModuleActive && enableAnims
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val cmd = "rm -f /data/user/0/com.android.launcher3/cache/p3_anim_crash_guard && am force-stop com.android.launcher3"
                    val result = runCatching {
                        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                        process.waitFor() == 0
                    }.getOrDefault(false)

                    Toast.makeText(
                        context,
                        if (result) "Счетчик сброшен, лаунчер перезапущен" else "Ошибка Root-доступа",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Перезапустить лаунчер (Root)")
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
