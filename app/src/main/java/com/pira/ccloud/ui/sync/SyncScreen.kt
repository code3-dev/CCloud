package com.pira.ccloud.ui.sync

import android.text.format.DateUtils
import android.view.InputDevice
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.pira.ccloud.components.focusRing
import com.pira.ccloud.components.initialFocus
import com.pira.ccloud.sync.ClaimOutcome
import com.pira.ccloud.sync.SyncEngine
import com.pira.ccloud.sync.SyncStatus
import com.pira.ccloud.sync.android.SyncService
import com.pira.ccloud.sync.transport.DeviceSummary
import com.pira.ccloud.sync.transport.PairingCodes
import com.pira.ccloud.sync.transport.PairingKind
import com.pira.ccloud.sync.transport.PairingStatus
import com.pira.ccloud.sync.transport.PairingTicket
import com.pira.ccloud.sync.transport.SyncTransportException
import com.pira.ccloud.utils.DeviceUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Sync screen shows. */
private enum class SyncMode {
    /** Status, devices and actions. */
    MAIN,

    /** This device's QR code and code: a join request, or an invite while syncing. */
    SHOW_CODE,

    /** The camera, to scan another device's code (phones). */
    SCAN,

    /** A field to type another device's code. */
    ENTER_CODE
}

/**
 * Settings → Sync: turning sync on, linking devices with QR codes (in both directions, since TVs
 * and some devices have no camera), the group's devices, leaving and deleting.
 *
 * [initialCode] comes from a scanned `ccloud://pair?code=` link and is used right away.
 */
@Composable
fun SyncScreen(navController: NavController, initialCode: String? = null) {
    val context = LocalContext.current
    val engine = remember { SyncService.engine(context) }
    if (engine == null) {
        // Sync is hidden in this build
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val status by engine.status.collectAsState()
    val scope = rememberCoroutineScope()
    val isTv = remember { DeviceUtils.isTv(context) }
    val usesRemote = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    var mode by remember { mutableStateOf(SyncMode.MAIN) }
    var isBusy by remember { mutableStateOf(false) }
    var switchCode by remember { mutableStateOf<String?>(null) }
    var isConfirmingDelete by remember { mutableStateOf(false) }
    // The scanner reports one code; a new value starts it again
    var scanAttempt by remember { mutableLongStateOf(0L) }

    fun rescanSoon() {
        scope.launch {
            // Not at once, or the same code is read again right away
            delay(RESCAN_DELAY_MS)
            scanAttempt++
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    fun explain(error: Exception) {
        // Leaving the screen cancels its work; that's not an error to show
        if (error is CancellationException) return
        toast(
            when (error) {
                is SyncTransportException.InvalidCode -> "This code doesn't work. Ask the other device for a new one."
                is SyncTransportException.Unavailable -> "Can't reach the sync server. Check the connection and try again."
                is SyncTransportException.RateLimited -> "Too many tries. Wait a few minutes and try again."
                else -> "Something went wrong. Try again."
            }
        )
    }

    fun claim(code: String) {
        scope.launch {
            isBusy = true
            try {
                when (engine.claim(code)) {
                    ClaimOutcome.JOINED -> toast("This device now syncs with your other devices")
                    ClaimOutcome.APPROVED -> toast("The other device is linked")
                    ClaimOutcome.NEEDS_SWITCH -> switchCode = code
                }
                mode = SyncMode.MAIN
            } catch (e: Exception) {
                explain(e)
                if (mode == SyncMode.SCAN) rescanSoon()
            } finally {
                isBusy = false
            }
        }
    }

    // A code from a scanned link, used once (not again after the activity is recreated)
    var usedInitialCode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialCode) {
        if (usedInitialCode) return@LaunchedEffect
        usedInitialCode = true
        initialCode?.let(PairingCodes::normalize)?.let { claim(it) }
    }

    BackHandler(enabled = mode != SyncMode.MAIN) { mode = SyncMode.MAIN }

    switchCode?.let { code ->
        AlertDialog(
            onDismissRequest = { switchCode = null },
            title = { Text("Switch sync group?") },
            text = { Text("This device already syncs with other devices. It will leave them and join the new group, bringing its data along.") },
            confirmButton = {
                TextButton(onClick = {
                    switchCode = null
                    scope.launch {
                        isBusy = true
                        try {
                            engine.switchGroup(code)
                            toast("This device now syncs with the new group")
                        } catch (e: Exception) {
                            explain(e)
                        } finally {
                            isBusy = false
                        }
                    }
                }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { switchCode = null }) { Text("Cancel") } }
        )
    }

    if (isConfirmingDelete) {
        AlertDialog(
            onDismissRequest = { isConfirmingDelete = false },
            title = { Text("Delete sync data?") },
            text = { Text("Sync stops on all your devices and the copy kept for syncing is deleted. Each device keeps its own favorites and history.") },
            confirmButton = {
                TextButton(onClick = {
                    isConfirmingDelete = false
                    scope.launch {
                        try {
                            engine.deleteGroup()
                            toast("Sync data deleted")
                        } catch (e: Exception) {
                            explain(e)
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { isConfirmingDelete = false }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (mode == SyncMode.MAIN) navController.popBackStack() else mode = SyncMode.MAIN },
                modifier = Modifier.focusRing(CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Sync",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
            if (isBusy) {
                Spacer(Modifier.width(16.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        when (mode) {
            SyncMode.MAIN -> MainContent(
                engine = engine,
                status = status,
                isTv = isTv,
                usesRemote = usesRemote,
                onTurnOn = {
                    scope.launch {
                        isBusy = true
                        try {
                            engine.turnOn()
                            toast("Sync is on. Add your other devices with a code.")
                        } catch (e: Exception) {
                            explain(e)
                        } finally {
                            isBusy = false
                        }
                    }
                },
                onShowCode = { mode = SyncMode.SHOW_CODE },
                onScan = { mode = SyncMode.SCAN },
                onEnterCode = { mode = SyncMode.ENTER_CODE },
                onLeave = {
                    scope.launch {
                        engine.leave()
                        toast("Sync is off on this device. Its data stays here.")
                    }
                },
                onDelete = { isConfirmingDelete = true },
                onError = ::explain
            )
            SyncMode.SHOW_CODE -> ShowCodeContent(
                engine = engine,
                isTv = isTv,
                usesRemote = usesRemote,
                onLinked = { kind ->
                    toast(if (kind == PairingKind.REQUEST) "This device now syncs with your other devices" else "The other device is linked")
                    mode = SyncMode.MAIN
                },
                onCancel = { mode = SyncMode.MAIN },
                onError = { error ->
                    explain(error)
                    mode = SyncMode.MAIN
                }
            )
            SyncMode.SCAN -> ScanContent(
                scanAttempt = scanAttempt,
                onScanned = { text ->
                    val code = PairingCodes.normalize(text)
                    if (code == null) {
                        toast("That isn't a CCloud code")
                        rescanSoon()
                    } else {
                        claim(code)
                    }
                },
                onTypeInstead = { mode = SyncMode.ENTER_CODE },
                onCancel = { mode = SyncMode.MAIN },
                onPermissionDenied = {
                    toast("Without the camera, type the code instead")
                    mode = SyncMode.ENTER_CODE
                }
            )
            SyncMode.ENTER_CODE -> EnterCodeContent(
                usesRemote = usesRemote,
                onSubmit = ::claim,
                onCancel = { mode = SyncMode.MAIN }
            )
        }
    }
}

@Composable
private fun MainContent(
    engine: SyncEngine,
    status: SyncStatus,
    isTv: Boolean,
    usesRemote: Boolean,
    onTurnOn: () -> Unit,
    onShowCode: () -> Unit,
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
    onError: (Exception) -> Unit
) {
    val firstAction = remember { FocusRequester() }
    // Coming back from another mode, a remote's focus goes to the first action
    LaunchedEffect(status is SyncStatus.On) {
        if (usesRemote) {
            delay(50)
            try {
                firstAction.requestFocus()
            } catch (e: IllegalStateException) {
                // Not composed yet; ScreenFocus handles the first showing
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (status) {
            SyncStatus.Off -> {
                Text(
                    text = "Keep your favorites, playlists, watched episodes, where you stopped watching, and the font, " +
                        "seek and subtitle color settings the same on all your devices, phones and TVs, Android and Apple.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "No account is needed: devices link by scanning a code. If you lose all your devices, the synced copy is lost too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ActionButton(
                    "Turn on sync",
                    Icons.Default.Sync,
                    onTurnOn,
                    primary = true,
                    modifier = Modifier.initialFocus().focusRequester(firstAction)
                )
                ActionButton("Link to a device that already syncs", Icons.Default.QrCode, onShowCode)
                if (isTv) {
                    ActionButton("Join with a code", Icons.Default.Devices, onEnterCode)
                } else {
                    ActionButton("Join by scanning a code", Icons.Default.QrCodeScanner, onScan)
                    ActionButton("Join by typing a code", Icons.Default.Devices, onEnterCode)
                }
            }
            is SyncStatus.On -> {
                SyncedSummary(status)
                ActionButton(
                    "Add a device: show a code",
                    Icons.Default.QrCode,
                    onShowCode,
                    primary = true,
                    modifier = Modifier.initialFocus().focusRequester(firstAction)
                )
                if (!isTv) {
                    ActionButton("Add a device: scan its code", Icons.Default.QrCodeScanner, onScan)
                }
                ActionButton("Add a device: type its code", Icons.Default.Devices, onEnterCode)
                DeviceList(
                    engine = engine,
                    status = status,
                    onError = onError,
                    // The removed row had focus; don't let it fall back to the sidebar
                    onRemoved = { if (usesRemote) runCatching { firstAction.requestFocus() } }
                )
                Spacer(Modifier.height(8.dp))
                ActionButton("Stop syncing on this device", Icons.Default.Sync, onLeave)
                ActionButton("Delete sync data for all devices", Icons.Default.Delete, onDelete, destructive = true)
            }
        }
    }
}

@Composable
private fun SyncedSummary(status: SyncStatus.On) {
    // Refresh "2 minutes ago" now and then
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val last = status.lastSyncedAt
    val text = when {
        status.isSyncing -> "Syncing…"
        last == null -> "Waiting for the first sync"
        else -> "Last synced " + DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS)
    }
    Column {
        Text("Sync is on", style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.isOffline) {
            Text(
                "Can't reach the sync server right now. Changes are kept and sent later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun DeviceList(
    engine: SyncEngine,
    status: SyncStatus.On,
    onError: (Exception) -> Unit,
    onRemoved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceSummary>>(emptyList()) }
    var reload by remember { mutableLongStateOf(0L) }
    LaunchedEffect(status.groupId, status.lastSyncedAt, reload) {
        devices = try {
            engine.devices()
        } catch (e: Exception) {
            devices
        }
    }
    if (devices.isEmpty()) return
    Text("Devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    devices.forEach { device ->
        // The remove button sits beside the card's text, not inside a clickable card, so the
        // D-pad reaches it
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name + if (device.isSelf) " (this device)" else "", style = MaterialTheme.typography.bodyLarge)
                    Text(platformName(device.platform), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!device.isSelf) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    engine.removeDevice(device.id)
                                    reload++
                                    onRemoved()
                                } catch (e: Exception) {
                                    onError(e)
                                }
                            }
                        },
                        modifier = Modifier.focusRing(CircleShape)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${device.name}")
                    }
                }
            }
        }
    }
}

private fun platformName(platform: String) = when (platform) {
    "android" -> "Android"
    "android-tv" -> "Android TV"
    "ios" -> "iPhone or iPad"
    "tvos" -> "Apple TV"
    "macos" -> "Mac"
    else -> platform
}

@Composable
private fun ShowCodeContent(
    engine: SyncEngine,
    isTv: Boolean,
    usesRemote: Boolean,
    onLinked: (PairingKind) -> Unit,
    onCancel: () -> Unit,
    onError: (Exception) -> Unit
) {
    var ticket by remember { mutableStateOf<PairingTicket?>(null) }
    var isExpired by remember { mutableStateOf(false) }
    var attempt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val cancel = remember { FocusRequester() }

    // Get a code, then check every 2 s whether the other device used it
    LaunchedEffect(attempt) {
        isExpired = false
        ticket = null
        val current = try {
            engine.startPairing()
        } catch (e: Exception) {
            onError(e)
            return@LaunchedEffect
        }
        ticket = current
        while (true) {
            delay(POLL_INTERVAL_MS)
            now = System.currentTimeMillis()
            val status = try {
                engine.pollPairing(current)
            } catch (e: SyncTransportException.Unavailable) {
                continue
            } catch (e: Exception) {
                onError(e)
                return@LaunchedEffect
            }
            when (status) {
                PairingStatus.Pending -> Unit
                PairingStatus.Expired -> {
                    isExpired = true
                    return@LaunchedEffect
                }
                is PairingStatus.Done -> {
                    onLinked(current.kind)
                    return@LaunchedEffect
                }
            }
        }
    }
    // Per the TV rules, focus starts on Cancel, not on the code
    LaunchedEffect(Unit) {
        if (usesRemote) {
            delay(50)
            try {
                cancel.requestFocus()
            } catch (e: IllegalStateException) {
                // Not composed yet
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val current = ticket
        val instructions = when (current?.kind) {
            PairingKind.INVITE -> "On the new device, open Settings → Sync, choose to join, and scan this code or type it."
            else -> "On a device that already syncs, open Settings → Sync → Add a device, and scan this code or type it."
        }
        Text(instructions, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Box(
            modifier = if (isTv) Modifier.fillMaxHeight(0.45f).aspectRatio(1f) else Modifier.fillMaxWidth(0.7f).aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                isExpired -> Text("This code expired", style = MaterialTheme.typography.titleMedium)
                current == null -> CircularProgressIndicator()
                else -> QrCode(current.qrPayload, contentDescription = "Pairing code", modifier = Modifier.fillMaxSize())
            }
        }
        if (current != null && !isExpired) {
            Text(
                text = PairingCodes.format(current.code),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (isTv) 44.sp else 32.sp,
                letterSpacing = 4.sp
            )
            val secondsLeft = ((current.expiresAt - now) / 1000).coerceAtLeast(0)
            Text(
                "Waiting for the other device… (${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isExpired) {
                Button(onClick = { attempt++ }, modifier = Modifier.focusRing(ButtonDefaults.shape)) { Text("New code") }
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancel).focusRing(ButtonDefaults.outlinedShape)
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun ScanContent(
    scanAttempt: Long,
    onScanned: (String) -> Unit,
    onTypeInstead: () -> Unit,
    onCancel: () -> Unit,
    onPermissionDenied: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Point the camera at the code on the other device", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        key(scanAttempt) {
            QrScanner(
                onScanned = onScanned,
                onPermissionDenied = onPermissionDenied,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onTypeInstead) { Text("Type the code instead") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun EnterCodeContent(usesRemote: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val code = PairingCodes.normalize(text)
    val cancel = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // As in SearchScreen: remotes that don't report themselves as D-pads would otherwise move the
    // text cursor with the arrows, trapping focus in the field
    fun handleRemoteKey(event: KeyEvent): Boolean {
        val device = event.nativeKeyEvent.device
        if (event.type != KeyEventType.KeyDown || device == null || device.isVirtual ||
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) {
            return false
        }
        return when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                keyboardController?.show()
                true
            }
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
            Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
            else -> false
        }
    }
    // With a remote, start on Cancel: focusing the field would open the keyboard at once
    LaunchedEffect(Unit) {
        if (usesRemote) {
            delay(50)
            try {
                cancel.requestFocus()
            } catch (e: IllegalStateException) {
                // Not composed yet
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Type the code shown on the other device", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(12) },
            label = { Text("Code") },
            placeholder = { Text("XXXX-XXXX") },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { code?.let(onSubmit) }),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { handleRemoteKey(it) }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { code?.let(onSubmit) },
                enabled = code != null,
                modifier = Modifier.focusRing(ButtonDefaults.shape)
            ) { Text("Link") }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancel).focusRing(ButtonDefaults.outlinedShape)
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false
) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
    if (primary) {
        Button(onClick = onClick, modifier = modifier.fillMaxWidth().focusRing(ButtonDefaults.shape)) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().focusRing(ButtonDefaults.outlinedShape),
            colors = if (destructive) {
                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.outlinedButtonColors()
            }
        ) { content() }
    }
}

private const val POLL_INTERVAL_MS = 2_000L
private const val RESCAN_DELAY_MS = 1_500L
