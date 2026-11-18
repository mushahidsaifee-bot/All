package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.startapp.sdk.ads.banner.Banner
import com.startapp.sdk.ads.banner.BannerListener
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AdDisplayRules {
  const val INTERSTITIAL_CALCULATION_INTERVAL = 8
  const val BANNER_MIN_CALCULATIONS = 8

  fun shouldShowInterstitialOnCalculation(calculationCount: Int): Boolean {
    return calculationCount >= INTERSTITIAL_CALCULATION_INTERVAL &&
      calculationCount % INTERSTITIAL_CALCULATION_INTERVAL == 0
  }

  fun shouldShowHistoryBanner(calculationCount: Int): Boolean {
    return calculationCount >= BANNER_MIN_CALCULATIONS
  }
}

object StartIoAdManager {
  @Volatile private var sdkInitialized = false
  @Volatile private var interstitialAd: StartAppAd? = null
  @Volatile private var isLoadingInterstitial = false

  fun initialize(context: Context, appId: String) {
    if (sdkInitialized) return
    synchronized(this) {
      if (sdkInitialized) return
      runCatching {
        val appContext = context.applicationContext
        // Turn off Start.io privacy policy / consent pop-up
        StartAppSDK.enableConsent(appContext, false)
        StartAppSDK.setUserConsent(appContext, "pas", System.currentTimeMillis(), true)
        // Initialize Start.io SDK with return ads disabled
        StartAppSDK.initParams(appContext, appId)
          .setReturnAdsEnabled(false)
          .init()
        StartAppSDK.enableReturnAds(false)
        // Show real ads (test ads disabled)
        StartAppSDK.setTestAdsEnabled(false)
        // Disable Start.io splash and auto-interstitial pop-ups
        StartAppAd.disableSplash()
        StartAppAd.disableAutoInterstitial()
        sdkInitialized = true
      }
    }
  }

  fun preloadInterstitial(context: Context, isOnline: Boolean) {
    if (!sdkInitialized || !isOnline || isLoadingInterstitial) return
    runCatching {
      val currentAd = interstitialAd ?: StartAppAd(context.applicationContext).also { interstitialAd = it }
      if (currentAd.isReady) return
      isLoadingInterstitial = true
      currentAd.loadAd(
        object : AdEventListener {
          override fun onReceiveAd(ad: Ad) {
            isLoadingInterstitial = false
          }

          override fun onFailedToReceiveAd(ad: Ad?) {
            isLoadingInterstitial = false
          }
        }
      )
    }.onFailure {
      isLoadingInterstitial = false
    }
  }

  fun showInterstitialIfReadyOrProceed(
    context: Context,
    isOnline: Boolean,
    onProceed: () -> Unit,
  ) {
    val proceeded = AtomicBoolean(false)
    fun proceedOnce() {
      if (proceeded.compareAndSet(false, true)) {
        onProceed()
      }
    }

    if (!sdkInitialized || !isOnline) {
      proceedOnce()
      return
    }

    val currentAd = interstitialAd
    val isReady = runCatching { currentAd?.isReady == true }.getOrDefault(false)
    if (currentAd == null || !isReady) {
      preloadInterstitial(context, isOnline)
      proceedOnce()
      return
    }

    val shown =
      runCatching {
        currentAd.showAd(
          object : AdDisplayListener {
            override fun adHidden(ad: Ad) {
              interstitialAd = null
              preloadInterstitial(context, isOnline)
              proceedOnce()
            }

            override fun adDisplayed(ad: Ad) = Unit

            override fun adClicked(ad: Ad) = Unit

            override fun adNotDisplayed(ad: Ad) {
              interstitialAd = null
              preloadInterstitial(context, isOnline)
              proceedOnce()
            }
          }
        )
      }.getOrDefault(false)

    if (!shown) {
      proceedOnce()
    }
  }
}

class NetworkConnectivityMonitor(context: Context) {
  private val appContext = context.applicationContext
  private val connectivityManager =
    runCatching {
      appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }.getOrNull()

  private val _isOnline = MutableStateFlow(checkCurrentlyOnline())
  val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

  private var callbackRegistered = false
  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        _isOnline.value = checkCurrentlyOnline()
      }

      override fun onLost(network: Network) {
        _isOnline.value = checkCurrentlyOnline()
      }

      override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
      ) {
        _isOnline.value =
          networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      }
    }

  fun start() {
    if (callbackRegistered) return
    runCatching {
      connectivityManager?.registerDefaultNetworkCallback(networkCallback)
      callbackRegistered = true
      _isOnline.value = checkCurrentlyOnline()
    }
  }

  fun stop() {
    if (!callbackRegistered) return
    runCatching {
      connectivityManager?.unregisterNetworkCallback(networkCallback)
    }
    callbackRegistered = false
  }

  private fun checkCurrentlyOnline(): Boolean {
    return runCatching {
      val cm = connectivityManager ?: return@runCatching false
      val network = cm.activeNetwork ?: return@runCatching false
      val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
  }
}

@Composable
fun rememberIsNetworkAvailable(): Boolean {
  val context = LocalContext.current
  val monitor = remember(context) { NetworkConnectivityMonitor(context) }
  var isOnline by remember(monitor) { mutableStateOf(monitor.isOnline.value) }

  DisposableEffect(monitor) {
    monitor.start()
    isOnline = monitor.isOnline.value
    onDispose { monitor.stop() }
  }

  LaunchedEffect(monitor) {
    monitor.isOnline.collect { online ->
      isOnline = online
    }
  }

  return isOnline
}

@Composable
fun StartIoHistoryBanner(
  calculationCount: Int,
  isOnline: Boolean,
  modifier: Modifier = Modifier,
) {
  val shouldShow = AdDisplayRules.shouldShowHistoryBanner(calculationCount) && isOnline
  if (!shouldShow) {
    return
  }

  var isAdLoaded by remember { mutableStateOf(false) }

  DisposableEffect(shouldShow) {
    onDispose {
      isAdLoaded = false
    }
  }

  Box(
    modifier = modifier.fillMaxWidth().testTag("history_banner_container"),
    contentAlignment = Alignment.Center,
  ) {
    Layout(
      modifier = Modifier.fillMaxWidth().clipToBounds(),
      content = {
        AndroidView(
          factory = { ctx ->
            Banner(ctx).apply {
              setBannerListener(
                object : BannerListener {
                  override fun onReceiveAd(banner: View) {
                    isAdLoaded = true
                  }

                  override fun onFailedToReceiveAd(banner: View?) {
                    isAdLoaded = false
                  }

                  override fun onImpression(banner: View) = Unit

                  override fun onClick(banner: View) = Unit
                }
              )
              runCatching { loadAd() }
            }
          },
        )
      },
    ) { measurables, constraints ->
      val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
      val placeable = measurables.firstOrNull()?.measure(childConstraints)
      if (isAdLoaded && placeable != null && placeable.width > 0 && placeable.height > 0) {
        val xOffset = ((constraints.maxWidth - placeable.width) / 2).coerceAtLeast(0)
        layout(constraints.maxWidth, placeable.height) {
          placeable.placeRelative(xOffset, 0)
        }
      } else {
        // Zero height and zero width when ad is not loaded so no empty space remains
        layout(0, 0) {
          placeable?.placeRelative(0, 0)
        }
      }
    }
  }
}
