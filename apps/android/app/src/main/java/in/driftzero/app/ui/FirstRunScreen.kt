package `in`.driftzero.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import `in`.driftzero.app.R
import `in`.driftzero.app.settings.CalibrationStore
import `in`.driftzero.core.CalibrationStatus
import `in`.driftzero.core.StationaryCalibrator
import kotlinx.coroutines.delay

@Composable
internal fun FirstRunScreen(
    speedMps: Double?,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        denied = result.values.none { it }
        if (result.values.any { it } || denied) {
            step = 1
        }
    }
    when (step) {
        0 -> ScreenScaffold(
            title = stringResource(R.string.firstrun_location_title),
            onBack = if (FirstRunMount.locationBackOpensMap()) onFinished else { { step = 1 } },
            backLabel = stringResource(R.string.action_skip),
        ) {
            BasicText(
                text = if (denied) {
                    stringResource(R.string.firstrun_location_denied)
                } else {
                    stringResource(R.string.firstrun_what)
                },
                style = InstrumentTheme.type.body,
            )
            if (!denied) {
                BasicText(
                    text = stringResource(R.string.firstrun_why_location),
                    style = InstrumentTheme.type.body,
                )
            }
            PrimaryButton(
                label = stringResource(if (denied) R.string.action_continue else R.string.action_allow),
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted || denied) {
                        step = 1
                    } else {
                        launcher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!denied) {
                SecondaryButton(
                    label = stringResource(R.string.action_not_now),
                    onClick = { denied = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        else -> MountStep(
            speedMps = speedMps,
            onBack = { step = 0 },
            onFinished = onFinished,
        )
    }
}

@Composable
private fun MountStep(
    speedMps: Double?,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { CalibrationStore.open(context) }
    val manager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val hasGyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    val hasAccel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    val cal = remember { StationaryCalibrator() }
    var status by remember { mutableStateOf<CalibrationStatus>(CalibrationStatus.Still) }
    var running by remember { mutableStateOf(false) }
    var progressPct by remember { mutableIntStateOf(0) }
    var startedNs by remember { mutableStateOf(0L) }
    val samples = remember { StillSampleClock() }
    val tooFast = FirstRunMount.tooFast(speedMps)
    val missingSensors = !hasAccel || !hasGyro
    val missing = when {
        !hasAccel -> stringResource(R.string.firstrun_no_accel)
        !hasGyro -> stringResource(R.string.firstrun_no_gyro)
        else -> null
    }

    fun startWindow() {
        cal.reset()
        samples.reset()
        startedNs = SystemClock.elapsedRealtimeNanos()
        status = CalibrationStatus.Still
        progressPct = 0
        running = true
    }

    DisposableEffect(running) {
        if (!running || !hasAccel) {
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
                    return
                }
                val ts = event.timestamp
                cal.ingestAccel(
                    ts,
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble(),
                )
                samples.onSample(ts)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(
            listener,
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME,
        )
        onDispose { manager.unregisterListener(listener) }
    }
    LaunchedEffect(Unit) {
        if (!tooFast && hasAccel && hasGyro) {
            startWindow()
        }
    }
    LaunchedEffect(tooFast, running) {
        if (FirstRunMount.abortBecauseMoving(tooFast, running)) {
            running = false
            status = CalibrationStatus.FailedMoving
        }
    }
    LaunchedEffect(running) {
        if (!running) {
            return@LaunchedEffect
        }
        while (running) {
            val now = SystemClock.elapsedRealtimeNanos()
            val next = if (FirstRunMount.timedOutWithoutSamples(samples.lastNs, startedNs, now)) {
                CalibrationStatus.FailedShort
            } else {
                cal.evaluate(
                    FirstRunMount.evaluateNowNs(samples.firstNs, samples.lastNs, startedNs, now),
                    hasGyro,
                )
            }
            status = next
            progressPct = FirstRunMount.displayProgress(
                running = true,
                startedWallNs = startedNs,
                wallNs = now,
                firstSampleNs = samples.firstNs,
                lastSampleNs = samples.lastNs,
                status = next,
            )
            if (next is CalibrationStatus.Done ||
                next is CalibrationStatus.FailedShort ||
                next is CalibrationStatus.FailedMoving ||
                next is CalibrationStatus.FailedNoGyro
            ) {
                running = false
                if (FirstRunMount.shouldWriteProfile(next) && next is CalibrationStatus.Done) {
                    store.write(next.profile)
                    progressPct = 100
                    onFinished()
                }
            }
            delay(100)
        }
    }
    val word = when (val s = status) {
        CalibrationStatus.Still -> stringResource(R.string.calib_still)
        CalibrationStatus.Moving -> stringResource(R.string.calib_moving)
        is CalibrationStatus.Done -> stringResource(R.string.calib_done, s.tiltDeg.toInt().toString())
        CalibrationStatus.FailedShort -> stringResource(R.string.calib_failed_short)
        CalibrationStatus.FailedMoving -> stringResource(R.string.calib_failed_moving)
        CalibrationStatus.FailedNoGyro -> stringResource(R.string.calib_failed_no_gyro)
    }
    ScreenScaffold(title = stringResource(R.string.firstrun_mount_title), onBack = onBack) {
        BasicText(text = stringResource(R.string.firstrun_mount_still), style = InstrumentTheme.type.body)
        if (missing != null) {
            BasicText(text = missing, style = InstrumentTheme.type.caption)
        }
        BasicText(text = word, style = InstrumentTheme.type.readout)
        BasicText(text = "$progressPct%", style = InstrumentTheme.type.readout)
        if (tooFast) {
            BasicText(text = stringResource(R.string.firstrun_mount_moving), style = InstrumentTheme.type.caption)
        }
        when (FirstRunMount.primaryAction(status, missingSensors, running)) {
            MountPrimaryAction.FINISH -> PrimaryButton(
                label = stringResource(R.string.action_done),
                onClick = onFinished,
                modifier = Modifier.fillMaxWidth(),
            )
            MountPrimaryAction.START, MountPrimaryAction.RETRY -> PrimaryButton(
                label = stringResource(R.string.action_start),
                onClick = { startWindow() },
                enabled = !tooFast,
                modifier = Modifier.fillMaxWidth(),
            )
            MountPrimaryAction.NONE -> Unit
        }
        if (FirstRunMount.showSkip(status, missingSensors)) {
            SecondaryButton(
                label = stringResource(R.string.action_skip),
                onClick = onFinished,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
