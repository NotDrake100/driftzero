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
        0 -> ScreenScaffold(title = stringResource(R.string.firstrun_location_title), onBack = onFinished) {
            BasicText(
                text = if (denied) {
                    stringResource(R.string.firstrun_location_denied)
                } else {
                    stringResource(R.string.firstrun_location_body)
                },
                style = InstrumentTheme.type.body,
            )
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
                    onClick = {
                        denied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        1 -> SensorStep(onContinue = { step = 2 })
        else -> MountStep(speedMps = speedMps, onFinished = onFinished)
    }
}

@Composable
private fun SensorStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    val mag = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
    val gnss = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    ScreenScaffold(title = stringResource(R.string.firstrun_sensors_title), onBack = onContinue) {
        ListRow(stringResource(R.string.firstrun_sensor_accel), present(accel))
        ListRow(stringResource(R.string.firstrun_sensor_gyro), present(gyro))
        ListRow(stringResource(R.string.firstrun_sensor_mag), present(mag))
        ListRow(stringResource(R.string.firstrun_sensor_gnss), present(gnss))
        if (!gyro) {
            BasicText(text = stringResource(R.string.firstrun_no_gyro), style = InstrumentTheme.type.caption)
        }
        if (!accel) {
            BasicText(text = stringResource(R.string.firstrun_no_accel), style = InstrumentTheme.type.caption)
        }
        PrimaryButton(
            label = stringResource(R.string.action_continue),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun present(ok: Boolean): String =
    stringResource(if (ok) R.string.value_present else R.string.value_missing)

@Composable
private fun MountStep(speedMps: Double?, onFinished: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CalibrationStore.open(context) }
    val existing = remember { store.read() }
    val manager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val hasGyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    val hasAccel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    val cal = remember { StationaryCalibrator() }
    var status by remember { mutableStateOf<CalibrationStatus>(CalibrationStatus.Still) }
    var running by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val tooFast = (speedMps ?: 0.0) > 1.0
    DisposableEffect(running) {
        if (!running || !hasAccel) {
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    cal.ingestAccel(
                        event.timestamp,
                        event.values[0].toDouble(),
                        event.values[1].toDouble(),
                        event.values[2].toDouble(),
                    )
                }
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
    LaunchedEffect(running) {
        if (!running) {
            return@LaunchedEffect
        }
        while (running) {
            status = cal.evaluate(SystemClock.elapsedRealtimeNanos(), hasGyro)
            if (status is CalibrationStatus.Done ||
                status is CalibrationStatus.FailedShort ||
                status is CalibrationStatus.FailedMoving ||
                status is CalibrationStatus.FailedNoGyro
            ) {
                running = false
                val done = status as? CalibrationStatus.Done
                if (done != null) {
                    store.write(done.profile)
                }
            }
            delay(100)
        }
    }
    val word = when (val s = status) {
        CalibrationStatus.Still -> stringResource(R.string.calib_still)
        CalibrationStatus.Moving -> stringResource(R.string.calib_moving)
        is CalibrationStatus.Done -> stringResource(
            R.string.calib_done,
            s.tiltDeg.toInt().toString(),
        )
        CalibrationStatus.FailedShort -> stringResource(R.string.calib_failed_short)
        CalibrationStatus.FailedMoving -> stringResource(R.string.calib_failed_moving)
        CalibrationStatus.FailedNoGyro -> stringResource(R.string.calib_failed_no_gyro)
    }
    val canSkip = existing != null
    ScreenScaffold(title = stringResource(R.string.firstrun_mount_title), onBack = onFinished) {
        BasicText(text = stringResource(R.string.firstrun_mount_body), style = InstrumentTheme.type.body)
        BasicText(text = word, style = InstrumentTheme.type.readout)
        if (tooFast) {
            BasicText(text = stringResource(R.string.firstrun_mount_moving), style = InstrumentTheme.type.caption)
        }
        if (note != null) {
            BasicText(text = note!!, style = InstrumentTheme.type.caption)
        }
        PrimaryButton(
            label = stringResource(
                if (status is CalibrationStatus.Done) R.string.action_done else R.string.action_start,
            ),
            onClick = {
                if (status is CalibrationStatus.Done) {
                    onFinished()
                    return@PrimaryButton
                }
                if (tooFast) {
                    note = context.getString(R.string.firstrun_mount_moving)
                    return@PrimaryButton
                }
                cal.reset()
                status = CalibrationStatus.Still
                running = true
                note = null
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        )
        if (canSkip) {
            SecondaryButton(
                label = stringResource(R.string.action_skip),
                onClick = onFinished,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
