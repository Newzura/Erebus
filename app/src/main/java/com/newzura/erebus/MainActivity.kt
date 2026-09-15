package com.newzura.erebus

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.newzura.erebus.AppConstants.FREE_DROID_WARN_SOLUTIONS_URL
import com.newzura.erebus.AppConstants.FREE_DROID_WARN_VERSION_KEY
import com.newzura.erebus.AppConstants.GITHUB_REPO_URL
import com.newzura.erebus.AppConstants.KEEP_ANDROID_OPEN_URL
import com.newzura.erebus.AppConstants.MENU_BUTTON_AUTO_HIDE_DELAY_MS
import com.newzura.erebus.AppConstants.MENU_BUTTON_SHOW_DELAY_MS
import com.newzura.erebus.AppConstants.REQUEST_CODE_POST_NOTIFICATIONS
import com.newzura.erebus.AppConstants.REQUEST_CODE_RECORD_AUDIO
import com.newzura.erebus.bookmarks.BookmarkManager
import com.newzura.erebus.data.BrowserPreferences
import com.newzura.erebus.data.SiteIconCache
import com.newzura.erebus.databinding.ActivityMainBinding
import com.newzura.erebus.model.QuickActionButtonMode
import com.newzura.erebus.model.UserAgentProfile
import com.newzura.erebus.navigation.NavigationManager
import com.newzura.erebus.permissions.PermissionManager
import com.newzura.erebus.startpage.StartPageManager
import com.newzura.erebus.tabs.BrowserTab
import com.newzura.erebus.tabs.TabManager
import com.newzura.erebus.ui.BrowserUIManager
import com.newzura.erebus.ui.MainActivitySetup
import com.newzura.erebus.ui.OverlayManager
import com.newzura.erebus.ui.ThemeManager
import com.newzura.erebus.web.BrowserCallbacks
import com.newzura.erebus.web.releaseCompletely
import com.newzura.erebus.web.updateDesktopMode
import com.newzura.erebus.web.updateUserAgentProfile

/**
 * MainActivity serves as the central hub for the Erebus application.
 * It coordinates various feature managers to provide a modular and maintainable browser.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler: Handler = Handler(Looper.getMainLooper())
    
    // Feature Managers
        
    private val themeManager: ThemeManager by lazy { 
        ThemeManager(this, binding) 
    }
    
    private val permissionManager: PermissionManager by lazy { 
        PermissionManager(this) 
    }
    
    private val bookmarkManager: BookmarkManager by lazy { 
        BookmarkManager(this, binding, createBookmarkCallbacks()) 
    }
    
    private val startPageManager: StartPageManager by lazy { 
        StartPageManager(this, binding, bookmarkManager, createStartPageCallbacks()) 
    }
    
    private val tabManager: TabManager by lazy { 
        TabManager(this, binding, bookmarkManager, createTabCallbacks()) 
    }
    
    private val uiManager: BrowserUIManager by lazy { 
        BrowserUIManager(this, binding, tabManager, bookmarkManager, startPageManager, createUICallbacks()) 
    }
    
    private val navigationManager: NavigationManager by lazy { 
        NavigationManager(this, binding, tabManager, permissionManager, startPageManager, uiManager, createNavigationCallbacks()) 
    }
    
    private val overlayManager: OverlayManager by lazy { 
        OverlayManager(this, binding, tabManager, bookmarkManager, startPageManager, uiManager, createOverlayCallbacks()) 
    }

    // Erebus Bridge Client pour la connexion au pont Wi-Fi/USB
    private val bridgeClient = ErebusBridgeClient()

    private val isDebugBuild: Boolean by lazy { 
        val flags = applicationInfo.flags
        (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 
    }

    // Application State
    var webView: android.webkit.WebView? = null
        private set
        
    var currentUrl: String = ""
        private set
        
    var currentPageTitle: String = ""
        private set
        
    private var currentUserAgentProfile: UserAgentProfile = UserAgentProfile.ANDROID_CHROME
    private var shouldForceSessionRestore: Boolean = false
    
    var latestReleaseUrl: String = "https://github.com/kododake/Erebus/releases"
        private set

    // Proxy methods for MainActivitySetup
    val currentUrlProxy: String
        get() {
            return currentUrl
        }
    val latestReleaseUrlProxy: String
        get() {
            return latestReleaseUrl
        }
    
    fun updateNavigationButtonsProxy() { 
        updateNavigationButtons() 
    }
    
    fun handleQuickActionButtonPressedProxy() { 
        handleQuickActionButtonPressed() 
    }
    
    fun showStartPageProxy() { 
        showStartPage() 
    }

    // Result Launchers
    private val pickStartPageBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        startPageManager.handleStartPageBackgroundPicked(uri)
        rebuildSettingsContent()
    }

    // UI Behavior
    private val autoHideMenuFab = Runnable {
        if (::binding.isInitialized && !startPageManager.isShowingStartPage && !BrowserPreferences.isQuickActionButtonAlwaysVisible(this)) {
            binding.menuFab.hide()
        }
    }
    
    private val showMenuFabRunnable = Runnable {
        if (::binding.isInitialized && !uiManager.isInFullscreen() && !binding.menuOverlay.isVisible) {
            binding.menuFab.show()
            if (!startPageManager.isShowingStartPage && !BrowserPreferences.isQuickActionButtonAlwaysVisible(this)) {
                handler.postDelayed(autoHideMenuFab, MENU_BUTTON_AUTO_HIDE_DELAY_MS)
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val scaled = newBase?.let { BrowserPreferences.createScaledContext(it) }
        super.attachBaseContext(scaled ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        AppCompatDelegate.setDefaultNightMode(BrowserPreferences.getThemeMode(this).nightMode)
        super.onCreate(savedInstanceState)
        
        shouldForceSessionRestore = (savedInstanceState != null)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.menuVersion.text = "v${BuildConfig.VERSION_NAME}"
        
        // Initialiser le client Erebus Bridge
        bridgeClient.setListener(object : ErebusBridgeClient.MessageListener {
            override fun onMessageReceived(message: String) {
                runOnUiThread {
                    Log.i("ErebusBridge", "Message reçu du Bridge: $message")
                }
            }
            override fun onConnectionLost() {
                runOnUiThread {
                    Log.w("ErebusBridge", "Connexion au Bridge perdue")
                }
            }
        })
        
        setupUi()
        setupBackPressHandling()
        
        permissionManager.ensureNotificationPermissionIfNeeded(REQUEST_CODE_POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = navigationManager.extractBrowsableUrl(intent)
        if (url != null) {
            navigationManager.loadUrlFromIntent(url)
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        themeManager.applyMenuHeaderColors()
        refreshHomePageMode()
        bookmarkManager.refreshBookmarks()
        tabManager.refreshTabs()
        startPageManager.refreshStartPage()
        syncUserAgentProfile()
        uiManager.applyPersistentAddressBarPreference()
        uiManager.applyQuickActionButtonPreferences()
    }

    override fun onPause() {
        uiManager.exitFullscreen()
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoHideMenuFab)
        handler.removeCallbacks(showMenuFabRunnable)
        uiManager.exitFullscreen()
        startPageManager.onDestroy()
        
        tabManager.browserTabs.forEach { tab ->
            tab.speechBridge.destroy()
            tab.webView.releaseCompletely()
        }
        binding.webViewContainer.removeAllViews()
        tabManager.browserTabs.clear()
        webView = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionManager.handleRequestPermissionsResult(requestCode, grantResults) { granted ->
            val speechTab = tabManager.browserTabs.firstOrNull { it.id == permissionManager.pendingSpeechBridgeTabId }
            speechTab?.speechBridge?.onPermissionResult(granted)
        }
    }

    private fun setupUi() {
        tabManager.initializeTabs(
            navigationManager.extractBrowsableUrl(intent),
            BrowserPreferences.getHomePageUrl(this),
            BrowserPreferences.getLastVisitedUrl(this),
            BrowserPreferences.shouldRestoreTabsOnLaunch(this),
            BrowserPreferences.shouldResumeLastPageOnLaunch(this),
            shouldForceSessionRestore
        )
        currentUserAgentProfile = BrowserPreferences.getUserAgentProfile(this)
        binding.desktopSwitch.isChecked = BrowserPreferences.shouldUseDesktopMode(this)
        binding.desktopSwitch.setOnCheckedChangeListener { _, isChecked ->
            BrowserPreferences.setDesktopMode(this, isChecked)
            tabManager.browserTabs.forEach { tab ->
                tab.webView.updateDesktopMode(isChecked, currentUserAgentProfile)
            }
        }
        
        uiManager.configureAddressField(binding.addressEdit, binding.buttonClearAddress, binding.buttonGo, true)
        uiManager.configureAddressField(binding.persistentAddressEdit, binding.persistentButtonClearAddress, binding.persistentButtonGo, false)
        uiManager.syncAddressFieldsFrom(binding.addressEdit)
        uiManager.updateAddressClearButtons()
        uiManager.setupManualDragLogic()
        
        val setup = MainActivitySetup(
            this,
            binding,
            MainActivitySetup.Managers(
                bookmarkManager,
                startPageManager,
                tabManager,
                uiManager,
                navigationManager,
                overlayManager
            )
        )
        setup.setupClickListeners()
        
        updateNavigationButtons()
        tabManager.refreshTabs()
        startPageManager.refreshStartPage()
        showMenuButtonTemporarily()
        bookmarkManager.refreshBookmarks()
        refreshHomePageMode()
        uiManager.applyPersistentAddressBarPreference()
        uiManager.applyQuickActionButtonPreferences()
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                uiManager.isInFullscreen() -> uiManager.exitFullscreen()
                binding.checkLatestViewRoot.isVisible -> overlayManager.hideCheckLatestView()
                binding.qrCodeViewRoot.isVisible -> overlayManager.hideQrCodeView()
                binding.tabManagerRoot.isVisible -> tabManager.hideTabManager()
                binding.bookmarkManagerRoot.isVisible -> bookmarkManager.hideBookmarkManager()
                binding.settingsViewRoot.isVisible -> overlayManager.hideSettingsView()
                binding.menuOverlay.isVisible -> uiManager.hideMenuOverlay()
                startPageManager.isShowingStartPage && currentUrl.isNotBlank() -> hideStartPage()
                webView?.canGoBack() == true -> webView?.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
            updateNavigationButtons()
        }
    }

    private fun buildBrowserCallbacks(tab: BrowserTab): BrowserCallbacks {
        return BrowserCallbacks(
            onUrlChange = { url ->
                runOnUiThread {
                    tab.currentUrl = url
                    BrowserPreferences.persistUrl(this, url)
                    bookmarkManager.prefetchSiteIcon(url)
                    tabManager.persistTabSession()
                    if (tab.id == tabManager.activeTabId) {
                        currentUrl = url
                        if (!startPageManager.isShowingStartPage && binding.addressEdit.text?.toString() != url) {
                            binding.addressEdit.setText(url)
                            binding.addressEdit.setSelection(url.length)
                        }
                        updateNavigationButtons()
                        if (!startPageManager.isShowingStartPage) {
                            uiManager.updateConnectionSecurityIcon(url)
                        }
                        startPageManager.refreshStartPage()
                        tabManager.refreshTabs()
                    }
                }
            },
            onTitleChange = { title ->
                runOnUiThread {
                    tab.currentTitle = title.orEmpty()
                    tabManager.persistTabSession()
                    if (tab.id == tabManager.activeTabId) {
                        currentPageTitle = tab.currentTitle
                        if (!startPageManager.isShowingStartPage) {
                            binding.pageTitle.text = tab.currentTitle.ifBlank { tabManager.displayTitleForTab(tab) }
                        }
                    }
                    tabManager.refreshTabs()
                }
            },
            onFaviconReceived = { url, icon ->
                runOnUiThread {
                    SiteIconCache.cacheIcon(this, url, icon)
                    if (startPageManager.isShowingStartPage) startPageManager.refreshStartPage()
                    if (binding.bookmarkManagerRoot.isVisible) bookmarkManager.refreshBookmarks()
                    if (binding.tabManagerRoot.isVisible) tabManager.refreshTabs()
                }
            },
            onProgressChange = { p ->
                if (tab.id == tabManager.activeTabId) {
                    runOnUiThread {
                        updateProgress(p)
                    }
                }
            },
            onShowDownloadPrompt = { uri ->
                runOnUiThread {
                    uiManager.openUriExternally(uri)
                }
            },
            onCleartextNavigationRequested = { uri, once, host, cancel ->
                runOnUiThread {
                    permissionManager.showCleartextNavigationDialog(uri, once, host, cancel)
                }
            },
            onError = { _, d ->
                runOnUiThread {
                    if (isDebugBuild && tab.id == tabManager.activeTabId) {
                        Toast.makeText(
                            this,
                            d ?: getString(R.string.error_generic_message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onEnterFullscreen = { v, c ->
                runOnUiThread {
                    uiManager.enterFullscreen(v, c)
                }
            },
            onExitFullscreen = {
                runOnUiThread {
                    uiManager.exitFullscreen(true)
                }
            },
            onPermissionRequest = { r ->
                runOnUiThread {
                    permissionManager.handleWebPermissionRequest(r, REQUEST_CODE_RECORD_AUDIO)
                }
            },
            onGeolocationPermissionRequest = { origin, callback ->
                runOnUiThread {
                    permissionManager.handleGeolocationPermissionRequest(origin, callback)
                }
            }
        )
    }

    private fun showStartPage() { 
        startPageManager.showStartPage()
        webView?.visibility = View.INVISIBLE 
    }

    private fun hideStartPage() { 
        startPageManager.hideStartPage(currentPageTitle, currentUrl)
        webView?.visibility = View.VISIBLE 
    }
    
    private fun updateNavigationButtons() {
        val notStart = !startPageManager.isShowingStartPage
        val can = notStart && currentUrl.isNotBlank()
        binding.buttonBack.isEnabled = notStart && webView?.canGoBack() == true
        binding.buttonForward.isEnabled = notStart && webView?.canGoForward() == true
        binding.buttonReload.isEnabled = can
        binding.buttonExternal.isEnabled = can
        binding.desktopSwitch.isEnabled = notStart
        binding.desktopSwitch.alpha = if (notStart) 1.0f else 0.6f
    }

    private fun updateProgress(p: Int) { 
        binding.progressIndicator.isVisible = p in 1..99
        if (p in 1..99) {
            binding.progressIndicator.setProgressCompat(p, true)
        }
    }
    
    fun showMenuButtonTemporarily() {
        handler.removeCallbacks(showMenuFabRunnable)
        handler.removeCallbacks(autoHideMenuFab)
        if (uiManager.isInFullscreen() || binding.menuOverlay.isVisible) return
        if (startPageManager.isShowingStartPage || BrowserPreferences.isQuickActionButtonAlwaysVisible(this)) {
            binding.menuFab.show()
        } else {
            handler.postDelayed(showMenuFabRunnable, MENU_BUTTON_SHOW_DELAY_MS)
        }
    }

    /**
     * Teste la connexion au pont Erebus Bridge
     * Appelable depuis un bouton ou une action utilisateur
     */
    fun testBridgeConnection() {
        if (!bridgeClient.isConnecting()) {
            bridgeClient.connect()
            Toast.makeText(this, "Connexion au Bridge en cours...", Toast.LENGTH_SHORT).show()
        } else {
            bridgeClient.send("PING_FROM_ANDROID")
            Toast.makeText(this, "Ping envoyé au Bridge", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshHomePageMode() { 
        binding.buttonStartPage.isVisible =
            BrowserPreferences.getHomePageUrl(this).isNullOrBlank() 
    }
    
    private fun handleHomePagePreferenceChanged() {
        refreshHomePageMode()
        bookmarkManager.refreshBookmarks()
        startPageManager.refreshStartPage()
        rebuildSettingsContent()
        val url = BrowserPreferences.getHomePageUrl(this)
        if (!url.isNullOrBlank() && startPageManager.isShowingStartPage) {
            navigationManager.loadUrlFromIntent(url)
        } else {
            updateNavigationButtons()
        }
    }

    private fun rebuildSettingsContent() {
        if (::binding.isInitialized) {
            binding.settingsContentContainer.removeAllViews()
            if (binding.settingsViewRoot.isVisible) overlayManager.showSettingsView()
        }
    }
    
    private fun handleQuickActionButtonPressed() {
        val mode = BrowserPreferences.getQuickActionButtonMode(this)
        if (mode == QuickActionButtonMode.MENU) {
            uiManager.showMenuOverlay()
        } else {
            uiManager.showMenuOverlay(focusAddressBar = true)
        }
    }

    private fun syncUserAgentProfile() {
        val p = BrowserPreferences.getUserAgentProfile(this)
        if (p != currentUserAgentProfile) {
            currentUserAgentProfile = p
            tabManager.browserTabs.forEach { tab ->
                tab.webView.updateUserAgentProfile(p, BrowserPreferences.shouldUseDesktopMode(this))
            }
        }
    }

    
    private fun createBookmarkCallbacks() = object : BookmarkManager.BookmarkCallbacks {
        override fun onNavigateToUrl(url: String) { 
            navigationManager.loadUrlFromIntent(url)
            uiManager.hideMenuOverlay() 
        }
        
        override fun onRefreshStartPage() {
            startPageManager.refreshStartPage()
        }
        
        override fun onRebuildSettings() {
            rebuildSettingsContent()
        }
        
        override fun getCurrentUrl(): String {
            return currentUrl
        }
        
        override fun isShowingStartPage(): Boolean {
            return startPageManager.isShowingStartPage
        }
        
        override fun resolveThemeColor(attrRes: Int): Int {
            return themeManager.resolveThemeColor(attrRes)
        }
        
        override fun resolveReadableTextColor(backgroundColor: Int, preferredColor: Int, fallbackColor: Int): Int {
            return themeManager.resolveReadableTextColor(backgroundColor, preferredColor, fallbackColor)
        }
        
        override fun isHomePageEnabled(): Boolean {
            return BrowserPreferences.getHomePageUrl(this@MainActivity).isNullOrBlank().not()
        }
        
        override fun handleHomePagePreferenceChanged() {
            this@MainActivity.handleHomePagePreferenceChanged()
        }
    }

    private fun createStartPageCallbacks() = object : StartPageManager.StartPageCallbacks {
        override fun onNavigateToUrl(url: String) { 
            navigationManager.loadUrlFromIntent(url)
            uiManager.hideMenuOverlay() 
        }
        
        override fun onShowMenuOverlay() {
            uiManager.showMenuOverlay()
        }
        
        override fun onHideMenuOverlay() {
            uiManager.hideMenuOverlay()
        }
        
        override fun onEnterFullscreen() {
        }
        
        override fun onExitFullscreen() {
            uiManager.exitFullscreen()
        }
        
        override fun isInFullscreen(): Boolean {
            return uiManager.isInFullscreen()
        }
        
        override fun getCurrentUrl(): String {
            return currentUrl
        }
        
        override fun resolveThemeColor(attrRes: Int): Int {
            return themeManager.resolveThemeColor(attrRes)
        }
        
        override fun updateNavigationButtons() {
            this@MainActivity.updateNavigationButtons()
        }
        
        override fun updateConnectionSecurityIcon(url: String?) {
            uiManager.updateConnectionSecurityIcon(url)
        }
        
        override fun showMenuButtonTemporarily() {
            this@MainActivity.showMenuButtonTemporarily()
        }
        
        override fun loadUrlFromIntent(url: String) {
            navigationManager.loadUrlFromIntent(url)
        }
        
        override fun resolveReadableTextColor(bg: Int, pr: Int, fb: Int): Int {
            return themeManager.resolveReadableTextColor(bg, pr, fb)
        }
    }

    private fun createTabCallbacks() = object : TabManager.TabCallbacks {
        override fun onTabChanged(tab: BrowserTab) { 
            webView = tab.webView
            currentUrl = tab.currentUrl
            currentPageTitle = tab.currentTitle 
        }
        
        override fun buildBrowserCallbacks(tab: BrowserTab): BrowserCallbacks {
            return this@MainActivity.buildBrowserCallbacks(tab)
        }
        
        override fun onNavigateToUrl(url: String) {
            navigationManager.loadUrlFromIntent(url)
        }
        
        override fun onShowStartPage() {
            showStartPage()
        }
        
        override fun onHideStartPage() {
            hideStartPage()
        }
        
        override fun onShowMenuOverlay(focusAddressBar: Boolean) {
            uiManager.showMenuOverlay(focusAddressBar)
        }
        
        override fun onHideMenuOverlay() {
            uiManager.hideMenuOverlay()
        }
        
        override fun resolveThemeColor(attrRes: Int): Int {
            return themeManager.resolveThemeColor(attrRes)
        }
        
        override fun resolveReadableTextColor(backgroundColor: Int, preferredColor: Int, fallbackColor: Int): Int {
            return themeManager.resolveReadableTextColor(backgroundColor, preferredColor, fallbackColor)
        }
        
        override fun requestSpeechRecognitionMicrophoneAccess(tabId: Long, pageUrl: String?) {
            permissionManager.requestSpeechRecognitionMicrophoneAccess(tabId, pageUrl) { granted -> 
                val tab = tabManager.browserTabs.firstOrNull { it.id == tabId }
                tab?.speechBridge?.onPermissionResult(granted) 
            }
        }
        
        override fun onSpeechTabClosed(tabId: Long) {
            if (permissionManager.pendingSpeechBridgeTabId == tabId) {
                permissionManager.pendingSpeechBridgeTabId = null
            }
        }
        
        override fun sanitizeJsExternalUrl(sourceWebView: android.webkit.WebView, rawUrl: String?): Uri? {
            return uiManager.sanitizeJsExternalUrl(sourceWebView, rawUrl)
        }
        
        override fun openUriExternally(uri: Uri) {
            uiManager.openUriExternally(uri)
        }
        
        override fun updateNavigationButtons() {
            this@MainActivity.updateNavigationButtons()
        }
        
        override fun applyPersistentAddressBarPreference() {
            uiManager.applyPersistentAddressBarPreference()
        }
        
        override fun syncAddressFieldsFrom(source: com.google.android.material.textfield.TextInputEditText) {
            uiManager.syncAddressFieldsFrom(source)
        }
        
        override fun updateAddressClearButtons() {
            uiManager.updateAddressClearButtons()
        }
        
        override fun updateConnectionSecurityIcon(url: String?) {
            uiManager.updateConnectionSecurityIcon(url)
        }
        
        override fun showMenuButtonTemporarily() {
            this@MainActivity.showMenuButtonTemporarily()
        }
    }

    private fun createUICallbacks() = object : BrowserUIManager.UICallbacks {
        override fun onNavigateToAddress(raw: String, closeMenuAfterNavigate: Boolean) {
            navigationManager.navigateToAddress(raw, closeMenuAfterNavigate)
        }
        
        override fun onShowQrCodeView() {
            overlayManager.showQrCodeView(currentUrl)
        }
        
        override fun onShowCheckLatestView() {
            overlayManager.showCheckLatestView()
        }
        
        override fun onShowSettingsView() {
            overlayManager.showSettingsView()
        }
        
        override fun handleQuickActionButtonPressed() {
            this@MainActivity.handleQuickActionButtonPressed()
        }
        
        override fun resolveThemeColor(attrRes: Int): Int {
            return themeManager.resolveThemeColor(attrRes)
        }
        
        override fun showMenuButtonTemporarily() {
            this@MainActivity.showMenuButtonTemporarily()
        }
    }

    private fun createNavigationCallbacks() = object : NavigationManager.NavigationCallbacks {
        override fun onNavigationStarted(url: String) {
            currentUrl = url
        }
        
        override fun onNavigationFinished(url: String) {
            currentUrl = url
        }
        
        override fun onHideStartPage() {
            hideStartPage()
        }
        
        override fun getCurrentUrl(): String {
            return currentUrl
        }
        
        override fun setCurrentUrl(url: String) {
            currentUrl = url
        }
        
        override fun setCurrentPageTitle(title: String) {
            currentPageTitle = title
        }
    }

    private fun createOverlayCallbacks() = object : OverlayManager.OverlayCallbacks {
        override fun onRecreateRequested() {
            recreate()
        }
        
        override fun onHomePageChanged() {
            handleHomePagePreferenceChanged()
        }
        
        override fun onPickBackgroundRequested() {
            pickStartPageBackgroundLauncher.launch(arrayOf("image/*"))
        }
        
        override fun onVersionInfoReceived(latestUrl: String, tagName: String) {
            latestReleaseUrl = latestUrl
        }
    }
}
