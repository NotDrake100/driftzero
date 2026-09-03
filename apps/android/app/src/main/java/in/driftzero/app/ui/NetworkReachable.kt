package `in`.driftzero.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Snapshot of whether a route or search request can reach the network. */
internal fun networkReachable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
