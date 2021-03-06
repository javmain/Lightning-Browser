package acr.browser.lightning.browser

import acr.browser.lightning.BrowserApp
import acr.browser.lightning.R
import acr.browser.lightning.extensions.resizeAndShow
import acr.browser.lightning.html.bookmark.BookmarkPage
import acr.browser.lightning.html.download.DownloadsPage
import acr.browser.lightning.html.history.HistoryPage
import acr.browser.lightning.html.homepage.StartPage
import acr.browser.lightning.preference.UserPreferences
import acr.browser.lightning.search.SearchEngineProvider
import acr.browser.lightning.utils.FileUtils
import acr.browser.lightning.utils.UrlUtils
import acr.browser.lightning.view.*
import android.app.Activity
import android.app.Application
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.text.TextUtils
import android.util.Log
import android.webkit.URLUtil
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.rxkotlin.subscribeBy
import java.util.*
import javax.inject.Inject
import javax.inject.Named

/**
 * A manager singleton that holds all the [LightningView] and tracks the current tab. It handles
 * creation, deletion, restoration, state saving, and switching of tabs.
 */
class TabsManager {

    private val tabList = ArrayList<LightningView>(1)
    /**
     * Return the current [LightningView] or null if no current tab has been set.
     *
     * @return a [LightningView] or null if there is no current tab.
     */
    var currentTab: LightningView? = null
        private set
    private var tabNumberListeners: Set<((Int) -> Unit)> = hashSetOf()

    private var isInitialized = false
    private val postInitializationWorkList = ArrayList<() -> Unit>()

    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject internal lateinit var app: Application
    @Inject internal lateinit var searchEngineProvider: SearchEngineProvider
    @Inject @field:Named("database") internal lateinit var databaseScheduler: Scheduler
    @Inject @field:Named("disk") internal lateinit var diskScheduler: Scheduler
    @Inject @field:Named("main") internal lateinit var mainScheduler: Scheduler

    init {
        BrowserApp.appComponent.inject(this)
    }

    fun addTabNumberChangedListener(listener: ((Int) -> Unit)) {
        tabNumberListeners += listener
    }

    fun cancelPendingWork() = postInitializationWorkList.clear()

    fun doAfterInitialization(runnable: () -> Unit) {
        if (isInitialized) {
            runnable()
        } else {
            postInitializationWorkList.add(runnable)
        }
    }

    private fun finishInitialization() {
        isInitialized = true
        for (runnable in postInitializationWorkList) {
            runnable()
        }
    }

    /**
     * Restores old tabs that were open before the browser was closed. Handles the intent used to
     * open the browser.
     *
     * @param activity  the activity needed to create tabs.
     * @param intent    the intent that started the browser activity.
     * @param incognito whether or not we are in incognito mode.
     */
    fun initializeTabs(
        activity: Activity,
        intent: Intent?,
        incognito: Boolean
    ): Completable = Completable.create { emitter ->
        // Make sure we start with a clean tab list
        shutdown()

        val url: String? = if (intent?.action == Intent.ACTION_WEB_SEARCH) {
            extractSearchFromIntent(intent)
        } else {
            intent?.dataString
        }

        val tabInitializer = url?.let(::UrlInitializer)
            ?: HomePageInitializer(userPreferences, activity, databaseScheduler, mainScheduler)

        // If incognito, only create one tab
        if (incognito) {
            newTab(activity, tabInitializer, true)
            finishInitialization()
            emitter.onComplete()
            return@create
        }

        Log.d(TAG, "URL from intent: $url")
        currentTab = null
        if (userPreferences.restoreLostTabsEnabled) {
            restoreLostTabs(url, activity, emitter)
        } else {
            newTab(activity, tabInitializer, false)
            finishInitialization()
            emitter.onComplete()
        }
    }

    fun extractSearchFromIntent(intent: Intent): String? {
        val query = intent.getStringExtra(SearchManager.QUERY)
        val searchUrl = "${searchEngineProvider.getCurrentSearchEngine().queryUrl}${UrlUtils.QUERY_PLACE_HOLDER}"

        return if (query?.isNotBlank() == true) {
            UrlUtils.smartUrlFilter(query, true, searchUrl)
        } else {
            null
        }
    }

    private fun restoreLostTabs(
        newTabUrl: String?,
        activity: Activity,
        emitter: CompletableEmitter
    ) = restoreState()
        .subscribeOn(diskScheduler)
        .observeOn(mainScheduler)
        .subscribeBy(
            onNext = { bundle ->
                val item = requireNotNull(bundle)
                val url = item.getString(URL_KEY)
                if (url != null) {
                    val initializer = AsyncUrlInitializer(when {
                        UrlUtils.isBookmarkUrl(url) -> BookmarkPage(activity).createBookmarkPage()
                        UrlUtils.isDownloadsUrl(url) -> DownloadsPage().getDownloadsPage()
                        UrlUtils.isStartPageUrl(url) -> StartPage().createHomePage()
                        UrlUtils.isHistoryUrl(url) -> HistoryPage().createHistoryPage()
                        else -> StartPage().createHomePage()
                    }, databaseScheduler, mainScheduler)

                    newTab(activity, initializer, false)
                } else {
                    newTab(activity, BundleInitializer(item), false)
                }
            },
            onComplete = {
                if (newTabUrl != null) {
                    if (URLUtil.isFileUrl(newTabUrl)) {
                        AlertDialog.Builder(activity).apply {
                            setTitle(R.string.title_warning)
                            setMessage(R.string.message_blocked_local)
                            setOnDismissListener {
                                if (tabList.isEmpty()) {
                                    newTab(
                                        activity,
                                        HomePageInitializer(userPreferences, activity, databaseScheduler, mainScheduler),
                                        false
                                    )
                                }
                                finishInitialization()
                                emitter.onComplete()
                            }
                            setNegativeButton(android.R.string.cancel, null)
                            setPositiveButton(R.string.action_open) { _, _ ->
                                newTab(activity, UrlInitializer(newTabUrl), false)
                            }
                        }.resizeAndShow()
                    } else {
                        newTab(activity, UrlInitializer(newTabUrl), false)
                        finishInitialization()
                        emitter.onComplete()
                    }
                } else if (tabList.isEmpty()) {
                    newTab(
                        activity,
                        HomePageInitializer(userPreferences, activity, databaseScheduler, mainScheduler),
                        false
                    )
                    finishInitialization()
                    emitter.onComplete()
                } else {
                    finishInitialization()
                    emitter.onComplete()
                }
            }
        )


    /**
     * Method used to resume all the tabs in the browser. This is necessary because we cannot pause
     * the WebView when the app is open currently due to a bug in the WebView, where calling
     * onResume doesn't consistently resume it.
     *
     * @param context the context needed to initialize the LightningView preferences.
     */
    fun resumeAll(context: Context) {
        currentTab?.resumeTimers()
        for (tab in tabList) {
            tab.onResume()
            tab.initializePreferences(context)
        }
    }

    /**
     * Method used to pause all the tabs in the browser. This is necessary because we cannot pause
     * the WebView when the app is open currently due to a bug in the WebView, where calling
     * onResume doesn't consistently resume it.
     */
    fun pauseAll() {
        currentTab?.pauseTimers()
        tabList.forEach(LightningView::onPause)
    }

    /**
     * Return the tab at the given position in tabs list, or null if position is not in tabs list
     * range.
     *
     * @param position the index in tabs list
     * @return the corespondent [LightningView], or null if the index is invalid
     */
    fun getTabAtPosition(position: Int): LightningView? =
        if (position < 0 || position >= tabList.size) {
            null
        } else {
            tabList[position]
        }

    val allTabs: List<LightningView>
        get() = tabList

    /**
     * Shutdown the manager. This destroys all tabs and clears the references to those tabs. Current
     * tab is also released for garbage collection.
     */
    fun shutdown() {
        tabList.indices.forEach { deleteTab(0) }
        isInitialized = false
        currentTab = null
    }

    /**
     * Forwards network connection status to the WebViews.
     *
     * @param isConnected whether there is a network connection or not.
     */
    fun notifyConnectionStatus(isConnected: Boolean) = tabList.forEach {
        it.setNetworkAvailable(isConnected)
    }

    /**
     * The current number of tabs in the manager.
     *
     * @return the number of tabs in the list.
     */
    fun size(): Int = tabList.size

    /**
     * The index of the last tab in the manager.
     *
     * @return the last tab in the list or -1 if there are no tabs.
     */
    fun last(): Int = tabList.size - 1


    /**
     * The last tab in the tab manager.
     *
     * @return the last tab, or null if there are no tabs.
     */
    fun lastTab(): LightningView? = tabList.lastOrNull()

    /**
     * Create and return a new tab. The tab is automatically added to the tabs list.
     *
     * @param activity the activity needed to create the tab.
     * @param tabInitializer the initializer to run on the tab after it's been created.
     * @param isIncognito whether the tab is an incognito tab or not.
     * @return a valid initialized tab.
     */
    fun newTab(
        activity: Activity,
        tabInitializer: TabInitializer,
        isIncognito: Boolean
    ): LightningView {
        Log.d(TAG, "New tab")
        val tab = LightningView(activity, tabInitializer, isIncognito)
        tabList.add(tab)
        tabNumberListeners.forEach { it(size()) }
        return tab
    }

    /**
     * Removes a tab from the list and destroys the tab. If the tab removed is the current tab, the
     * reference to the current tab will be nullified.
     *
     * @param position The position of the tab to remove.
     */
    private fun removeTab(position: Int) {
        if (position >= tabList.size) {
            return
        }
        val tab = tabList.removeAt(position)
        if (currentTab == tab) {
            currentTab = null
        }
        tab.onDestroy()
    }

    /**
     * Deletes a tab from the manager. If the tab being deleted is the current tab, this method will
     * switch the current tab to a new valid tab.
     *
     * @param position the position of the tab to delete.
     * @return returns true if the current tab was deleted, false otherwise.
     */
    fun deleteTab(position: Int): Boolean {
        Log.d(TAG, "Delete tab: $position")
        val currentTab = currentTab
        val current = positionOf(currentTab)

        if (current == position) {
            when {
                size() == 1 -> this.currentTab = null
                current < size() - 1 -> switchToTab(current + 1)
                else -> switchToTab(current - 1)
            }
        }

        removeTab(position)
        tabNumberListeners.forEach { it(size()) }
        return current == position
    }

    /**
     * Return the position of the given tab.
     *
     * @param tab the tab to look for.
     * @return the position of the tab or -1 if the tab is not in the list.
     */
    fun positionOf(tab: LightningView?): Int = tabList.indexOf(tab)

    /**
     * Saves the state of the current WebViews, to a bundle which is then stored in persistent
     * storage and can be unparceled.
     */
    fun saveState() {
        val outState = Bundle(ClassLoader.getSystemClassLoader())
        Log.d(TAG, "Saving tab state")
        for (n in tabList.indices) {
            val tab = tabList[n]
            if (TextUtils.isEmpty(tab.url)) {
                continue
            }
            val state = Bundle(ClassLoader.getSystemClassLoader())
            val webView = tab.webView
            if (webView != null && !UrlUtils.isSpecialUrl(tab.url)) {
                webView.saveState(state)
                outState.putBundle(BUNDLE_KEY + n, state)
            } else if (webView != null) {
                state.putString(URL_KEY, tab.url)
                outState.putBundle(BUNDLE_KEY + n, state)
            }
        }
        FileUtils.writeBundleToStorage(app, outState, BUNDLE_STORAGE)
            .subscribeOn(diskScheduler)
            .subscribe()
    }

    /**
     * Use this method to clear the saved state if you do not wish it to be restored when the
     * browser next starts.
     */
    fun clearSavedState() = FileUtils.deleteBundleInStorage(app, BUNDLE_STORAGE)

    /**
     * Restores the previously saved tabs from the bundle stored in persistent file storage. It will
     * create new tabs for each tab saved and will delete the saved instance file when restoration
     * is complete.
     */
    private fun restoreState(): Observable<Bundle> = Maybe
        .fromCallable { FileUtils.readBundleFromStorage(app, BUNDLE_STORAGE) }
        .flattenAsObservable { bundle ->
            bundle.keySet()
                .filter { it.startsWith(BUNDLE_KEY) }
                .map(bundle::getBundle)
        }
        .doOnNext { Log.d(TAG, "Restoring previous WebView state now") }

    /**
     * Returns the index of the current tab.
     *
     * @return Return the index of the current tab, or -1 if the current tab is null.
     */
    fun indexOfCurrentTab(): Int = tabList.indexOf(currentTab)

    /**
     * Returns the index of the tab.
     *
     * @return Return the index of the tab, or -1 if the tab isn't in the list.
     */
    fun indexOfTab(tab: LightningView): Int = tabList.indexOf(tab)

    /**
     * Returns the [LightningView] with the provided hash, or null if there is no tab with the hash.
     *
     * @param hashCode the hashcode.
     * @return the tab with an identical hash, or null.
     */
    fun getTabForHashCode(hashCode: Int): LightningView? =
        tabList.firstOrNull { lightningView -> lightningView.webView?.let { it.hashCode() == hashCode } == true }

    /**
     * Switch the current tab to the one at the given position. It returns the selected tab that has
     * been switched to.
     *
     * @return the selected tab or null if position is out of tabs range.
     */
    fun switchToTab(position: Int): LightningView? {
        Log.d(TAG, "switch to tab: $position")
        return if (position < 0 || position >= tabList.size) {
            Log.e(TAG, "Returning a null LightningView requested for position: $position")
            null
        } else {
            val tab = tabList[position]
            currentTab = tab
            tab
        }
    }

    companion object {

        private const val TAG = "TabsManager"

        private const val BUNDLE_KEY = "WEBVIEW_"
        private const val URL_KEY = "URL_KEY"
        private const val BUNDLE_STORAGE = "SAVED_TABS.parcel"
    }

}
