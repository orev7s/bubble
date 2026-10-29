package com.accio.whisperbubble

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent { App() }
    }
}

private data class Perms(
    val mic: Boolean = false,
    val overlay: Boolean = false,
    val accessibility: Boolean = false
)

/** Keeps content readable on tablets instead of stretching it edge to edge. */
private val PhoneWidth = 460.dp
private val SettingsWidth = 560.dp

@Composable
private fun App() {
    var themeMode by remember { mutableStateOf(Prefs.themeMode) }
    var screen by remember { mutableStateOf(0) }

    BubbleTheme(themeMode) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            when (screen) {
                1 -> SettingsScreen(
                    themeMode = themeMode,
                    onTheme = {
                        themeMode = it
                        Prefs.themeMode = it
                    },
                    onDone = { screen = 0 }
                )
                else -> HomeScreen(onSettings = { screen = 1 })
            }
        }
    }
}

@Composable
private fun HomeScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    var perms by remember { mutableStateOf(Perms()) }
    var running by remember { mutableStateOf(OverlayService.alive) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        while (true) {
            perms = readPerms(context)
            running = OverlayService.alive
            delay(400)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = PhoneWidth)
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = 26.dp)
        ) {
            Spacer(Modifier.height(56.dp))

            Text(
                text = "Bubble",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    running && Prefs.trigger == Trigger.HOLD -> "on · hold"
                    running -> "on · tap"
                    else -> "off"
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.height(44.dp))

            PermRow("Microphone", perms.mic) { requestAudio(permissionLauncher) }
            PermRow("Overlay", perms.overlay) { openOverlaySettings(context) }
            PermRow("Accessibility", perms.accessibility) { openAccessibilitySettings(context) }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    when {
                        !perms.overlay -> openOverlaySettings(context)
                        !perms.mic -> requestAudio(permissionLauncher)
                        !perms.accessibility -> openAccessibilitySettings(context)
                        running -> {
                            context.stopService(Intent(context, OverlayService::class.java))
                            Prefs.running = false
                            running = false
                        }
                        else -> {
                            Prefs.running = true
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, OverlayService::class.java)
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background
                )
            ) {
                Text(
                    text = if (running) "Stop" else "Start",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsScreen(
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onDone: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var keys by remember { mutableStateOf(Prefs.keys) }
    var rotation by remember { mutableStateOf(Prefs.rotation) }
    var everyN by remember { mutableStateOf(Prefs.everyN.toString()) }
    var interval by remember { mutableStateOf(Prefs.intervalMinutes.toString()) }
    var delayMs by remember { mutableStateOf(Prefs.pressDelayMs) }
    var trigger by remember { mutableStateOf(Prefs.trigger) }
    var onlyFocused by remember { mutableStateOf(Prefs.onlyWhenFocused) }
    var language by remember { mutableStateOf(Prefs.language) }
    var checking by remember { mutableStateOf(false) }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    var keyOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = SettingsWidth)
                .fillMaxWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp)
        ) {
            Spacer(Modifier.height(28.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDone) {
                    Text("Done", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            Label("Keys")

            keys.forEach { key ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = KeyManager.mask(key),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        Prefs.keys = keys - key
                        keys = Prefs.keys
                    }) {
                        Text("Remove", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    keyMessage = null
                },
                label = { Text("New key", fontSize = 13.sp) },
                singleLine = true,
                isError = keyMessage != null && !keyOk,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            val message = keyMessage
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = if (keyOk) Color(0xFF3E9C5A) else MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val candidate = input.trim()
                    if (candidate.isEmpty() || checking) return@Button
                    scope.launch {
                        checking = true
                        keyMessage = null
                        val result = GroqApi.validate(candidate)
                        checking = false
                        if (result.error == null) {
                            if (!Prefs.keys.contains(candidate)) {
                                Prefs.keys = Prefs.keys + candidate
                                keys = Prefs.keys
                            }
                            keyOk = true
                            keyMessage = "Key works"
                            input = ""
                        } else {
                            keyOk = false
                            keyMessage = result.error
                        }
                    }
                },
                enabled = input.isNotBlank() && !checking,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (checking) "Checking" else "Add", fontSize = 14.sp)
            }

            Spacer(Modifier.height(30.dp))
            Label("Rotation")

            ChipGrid(
                options = listOf("Per request", "Every N", "Every N min", "On failure"),
                selected = when (rotation) {
                    Rotation.PER_REQUEST -> 0
                    Rotation.EVERY_N -> 1
                    Rotation.TIME_INTERVAL -> 2
                    Rotation.ON_FAILURE -> 3
                },
                onSelect = {
                    rotation = when (it) {
                        0 -> Rotation.PER_REQUEST
                        1 -> Rotation.EVERY_N
                        2 -> Rotation.TIME_INTERVAL
                        else -> Rotation.ON_FAILURE
                    }
                    Prefs.rotation = rotation
                }
            )

            if (rotation == Rotation.EVERY_N) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = everyN,
                    onValueChange = {
                        everyN = it.filter { c -> c.isDigit() }.take(3)
                        everyN.toIntOrNull()?.let { n -> Prefs.everyN = n.coerceAtLeast(1) }
                    },
                    label = { Text("Requests per key", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            if (rotation == Rotation.TIME_INTERVAL) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = interval,
                    onValueChange = {
                        interval = it.filter { c -> c.isDigit() }.take(5)
                        interval.toIntOrNull()?.let { n -> Prefs.intervalMinutes = n.coerceAtLeast(1) }
                    },
                    label = { Text("Minutes per key", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            Spacer(Modifier.height(30.dp))
            Label("Trigger")

            ChipGrid(
                options = listOf("Hold to talk", "Tap to toggle"),
                selected = if (trigger == Trigger.HOLD) 0 else 1,
                onSelect = {
                    trigger = if (it == 0) Trigger.HOLD else Trigger.TAP
                    Prefs.trigger = trigger
                }
            )

            Spacer(Modifier.height(22.dp))
            Label("Hold delay")

            ChipGrid(
                options = listOf("Instant", "150 ms", "300 ms"),
                selected = when {
                    delayMs <= 40 -> 0
                    delayMs <= 220 -> 1
                    else -> 2
                },
                onSelect = {
                    delayMs = when (it) {
                        0 -> 0
                        1 -> 150
                        else -> 300
                    }
                    Prefs.pressDelayMs = delayMs
                }
            )

            Spacer(Modifier.height(30.dp))
            Label("Theme")

            ChipGrid(
                options = listOf("Auto", "Light", "Dark"),
                selected = when (themeMode) {
                    ThemeMode.AUTO -> 0
                    ThemeMode.LIGHT -> 1
                    ThemeMode.DARK -> 2
                },
                onSelect = {
                    onTheme(
                        when (it) {
                            1 -> ThemeMode.LIGHT
                            2 -> ThemeMode.DARK
                            else -> ThemeMode.AUTO
                        }
                    )
                }
            )

            Spacer(Modifier.height(30.dp))
            Label("Bubble")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Only with text field", fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = onlyFocused,
                    onCheckedChange = {
                        onlyFocused = it
                        Prefs.onlyWhenFocused = it
                    }
                )
            }

            Spacer(Modifier.height(22.dp))
            Label("Language")

            OutlinedTextField(
                value = language,
                onValueChange = {
                    language = it.take(8)
                    Prefs.language = language
                },
                label = { Text("e.g. en, ar, fr — blank is auto", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(Modifier.height(50.dp))
        }
    }
}

@Composable
private fun PermRow(name: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted) { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.weight(1f))
        Text(
            text = if (granted) "on" else "grant",
            fontSize = 13.sp,
            fontWeight = if (granted) FontWeight.Normal else FontWeight.SemiBold,
            color = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun ChipGrid(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { columnIndex, label ->
                    val index = rowIndex * 2 + columnIndex
                    FilterChip(
                        selected = selected == index,
                        onClick = { onSelect(index) },
                        label = { Text(label, fontSize = 13.sp) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.onBackground,
                            selectedLabelColor = MaterialTheme.colorScheme.background,
                            labelColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                }
            }
        }
    }
}

private fun requestAudio(launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
    val request = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        request.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    launcher.launch(request.toTypedArray())
}

private fun readPerms(context: Context): Perms {
    val mic = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    return Perms(
        mic = mic,
        overlay = Settings.canDrawOverlays(context),
        accessibility = isAccessibilityEnabled(context)
    )
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, InputWatchService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
