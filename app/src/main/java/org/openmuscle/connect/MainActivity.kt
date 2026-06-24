package org.openmuscle.connect

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.openmuscle.connect.ui.CaptureScreen
import org.openmuscle.connect.ui.DevicePickerScreen
import org.openmuscle.connect.ui.HomeScreen
import org.openmuscle.connect.ui.InferenceScreen
import org.openmuscle.connect.ui.MultiCaptureScreen
import org.openmuscle.connect.ui.theme.OpenMuscleConnectTheme
import org.openmuscle.connect.viewmodel.CaptureViewModel
import org.openmuscle.connect.viewmodel.ConnectViewModel
import org.openmuscle.connect.viewmodel.DiscoveryViewModel
import org.openmuscle.connect.viewmodel.InferenceViewModel
import org.openmuscle.connect.viewmodel.MultiCaptureViewModel
import java.io.File

private enum class Route { PICKER, HOME, CAPTURE, INFER, MULTI_CAPTURE }

class MainActivity : ComponentActivity() {

    private val connectVm: ConnectViewModel by viewModels()
    private val discoveryVm: DiscoveryViewModel by viewModels()
    private val captureVm: CaptureViewModel by viewModels()
    private val multiCaptureVm: MultiCaptureViewModel by viewModels()
    private val inferenceVm: InferenceViewModel by viewModels()
    private var multicastLock: WifiManager.MulticastLock? = null

    // SAF picker for a .onnx model file exported by tools/export_onnx.py.
    private val modelPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { inferenceVm.loadModel(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acquireMulticastLock()

        setContent {
            OpenMuscleConnectTheme {
                val saved = remember { Prefs.selectedDeviceId(this) }
                var route by rememberSaveable {
                    mutableStateOf(if (saved != null) Route.HOME else Route.PICKER)
                }

                // Apply a previously chosen device filter once, on first launch.
                LaunchedEffect(Unit) { if (saved != null) connectVm.setFilter(saved) }
                // Listen only while the heatmap is on screen; discover only while
                // the picker is on screen (both free the UDP socket otherwise).
                LaunchedEffect(route) {
                    if (route == Route.HOME) connectVm.start() else connectVm.stop()
                    if (route == Route.PICKER) discoveryVm.start() else discoveryVm.stop()
                    // Pick up the latest role tags when entering multi-capture.
                    if (route == Route.MULTI_CAPTURE) multiCaptureVm.refreshSources()
                }

                when (route) {
                    Route.PICKER -> {
                        val devices by discoveryVm.state.collectAsState()
                        DevicePickerScreen(
                            devices = devices,
                            onSelect = { device ->
                                Prefs.setSelectedDeviceId(this, device.id)
                                connectVm.selectDevice(device)
                                route = Route.HOME
                            },
                            onSkip = {
                                Prefs.setSelectedDeviceId(this, null)
                                connectVm.setFilter(null)
                                route = Route.HOME
                            },
                            onRename = discoveryVm::renameDevice,
                            onSetRole = discoveryVm::setRole,
                            onMultiCapture = { route = Route.MULTI_CAPTURE },
                        )
                    }

                    Route.HOME -> {
                        val state by connectVm.state.collectAsState()
                        HomeScreen(
                            state = state,
                            onToggleListen = { if (state.listening) connectVm.stop() else connectVm.start() },
                            onBack = { route = Route.PICKER },
                            onCapture = { route = Route.CAPTURE },
                            onInfer = { route = Route.INFER },
                            onStartStream = connectVm::startStream,
                            onStopStream = connectVm::stopStream,
                            onScanRate = connectVm::setScanRate,
                        )
                    }

                    Route.INFER -> {
                        val inferState by inferenceVm.state.collectAsState()
                        InferenceScreen(
                            state = inferState,
                            onLoadModel = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                            onTogglePredict = {
                                if (inferState.predicting) inferenceVm.stop() else inferenceVm.start()
                            },
                            onBack = { route = Route.HOME },
                        )
                    }

                    Route.MULTI_CAPTURE -> {
                        val state by multiCaptureVm.state.collectAsState()
                        MultiCaptureScreen(
                            state = state,
                            onToggleRecord = {
                                if (state.recording) multiCaptureVm.stopCapture() else multiCaptureVm.startCapture()
                            },
                            onSetMirror = multiCaptureVm::setMirror,
                            onShare = ::shareSession,
                            onDelete = multiCaptureVm::deleteSession,
                            onBack = { route = Route.PICKER },
                        )
                    }

                    Route.CAPTURE -> {
                        val state by captureVm.state.collectAsState()
                        CaptureScreen(
                            state = state,
                            onToggleRecord = {
                                if (state.recording) captureVm.stopRecording() else captureVm.startRecording()
                            },
                            onManualModeChange = captureVm::setManualMode,
                            onManualLabelChange = captureVm::setManualLabel,
                            onShare = ::shareSession,
                            onDelete = captureVm::deleteSession,
                            onBack = { route = Route.HOME },
                        )
                    }
                }
            }
        }
    }

    /** Share a recorded session CSV out via the system share sheet. */
    private fun shareSession(name: String) {
        val file = File(File(filesDir, "sessions"), name)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share session"))
    }

    /**
     * Receiving UDP broadcast/multicast (the discovery beacon fallback, and some
     * routers' broadcast frames) requires holding a multicast lock on Android.
     * Unicast to the phone works without it; holding it is harmless.
     */
    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("om-connect").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    override fun onDestroy() {
        multicastLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
