package `in`.driftzero.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.driftzero.app.copy.DriverCopy
import `in`.driftzero.app.location.DeviceLocation
import `in`.driftzero.app.location.OriginSource
import `in`.driftzero.app.map.StreetMap
import `in`.driftzero.app.product.DemoPlaces
import kotlinx.coroutines.flow.collectLatest

@Composable
fun NavigationScreen(
    viewModel: NavigationUiViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.setLocationPermissionGranted(granted)
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    LaunchedEffect(state.locationPermissionGranted) {
        if (!state.locationPermissionGranted) {
            viewModel.onLiveFix(null)
            return@LaunchedEffect
        }
        DeviceLocation.observe(context.applicationContext).collectLatest { fix ->
            viewModel.onLiveFix(fix)
        }
    }

    val gpsLabel = when {
        state.gpsLost -> "GPS is weak"
        else -> DriverCopy.gpsHealthLabel(state.liveFix?.hasTrustedFix == true)
    }
    val speedLabel = DriverCopy.speedLabelOrNull(state.speedMetersPerSecond)
    val followUser = state.route == null && state.originSource == OriginSource.Gps && !state.gpsLost

    Box(modifier = Modifier.fillMaxSize()) {
        StreetMap(
            locationPermissionGranted = state.locationPermissionGranted,
            modifier = Modifier.fillMaxSize(),
            routePoints = state.route?.points.orEmpty(),
            destination = state.destination?.point,
            fallbackOrigin = state.origin ?: DemoPlaces.FALLBACK_ORIGIN,
            showFallbackOrigin = state.originSource == OriginSource.Fallback,
            cameraEpoch = state.cameraEpoch,
            followUser = followUser,
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
        ) {
            DestinationSearchBar(
                query = state.destinationQuery,
                onQueryChange = viewModel::setDestinationQuery,
                onSubmit = viewModel::requestStart,
                modifier = Modifier.fillMaxWidth(),
            )
            SearchResultsSheet(
                state = state,
                onPlaceSelected = viewModel::onPlaceSelected,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        DriverStatusBar(
            gpsLabel = gpsLabel,
            speedLabel = speedLabel,
            canStart = state.canStart,
            onStart = viewModel::requestStart,
            state = state,
            onDemoSuggestion = viewModel::onDemoSuggestion,
            onClear = viewModel::onClearDestination,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
