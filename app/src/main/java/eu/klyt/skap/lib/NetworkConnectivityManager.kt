package eu.klyt.skap.lib

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Manages network connectivity detection for the application
 */
class NetworkConnectivityManager(private val context: Context) {
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    /**
     * Checks if the device is currently connected to the internet
     */
    fun isConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            Log.e("NetworkConnectivity", "Error checking network connectivity", e)
            false
        }
    }
    
    /**
     * Registers a network callback to monitor connectivity changes
     */
    fun registerNetworkCallback(callback: NetworkCallback) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            Log.e("NetworkConnectivity", "Error registering network callback", e)
        }
    }
    
    /**
     * Unregisters a network callback
     */
    fun unregisterNetworkCallback(callback: NetworkCallback) {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.e("NetworkConnectivity", "Error unregistering network callback", e)
        }
    }
    
    /**
     * Abstract network callback class
     */
    abstract class NetworkCallback : ConnectivityManager.NetworkCallback() {
        abstract fun onConnectivityChanged(isConnected: Boolean)
        
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            onConnectivityChanged(true)
        }
        
        override fun onLost(network: Network) {
            super.onLost(network)
            onConnectivityChanged(false)
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val isConnected = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            onConnectivityChanged(isConnected)
        }
    }
}

/**
 * Composable function to observe network connectivity state
 */
@Composable
fun rememberNetworkConnectivityState(): Boolean {
    val context = LocalContext.current
    var isConnected by remember { 
        mutableStateOf(NetworkConnectivityManager(context).isConnected()) 
    }
    
    DisposableEffect(context) {
        val networkManager = NetworkConnectivityManager(context)
        
        val callback = object : NetworkConnectivityManager.NetworkCallback() {
            override fun onConnectivityChanged(connected: Boolean) {
                isConnected = connected
            }
        }
        
        networkManager.registerNetworkCallback(callback)
        
        // Update initial state
        isConnected = networkManager.isConnected()
        
        onDispose {
            networkManager.unregisterNetworkCallback(callback)
        }
    }
    
    return isConnected
}