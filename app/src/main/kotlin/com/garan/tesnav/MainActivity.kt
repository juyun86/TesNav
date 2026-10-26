package com.garan.tesnav

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.services.core.ServiceSettings
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.NavigationManeuver
import com.garan.tesnav.model.withDisplayExpiry
import com.garan.tesnav.export.NavAssistV2Protocol
import com.garan.tesnav.export.NAVASSIST_UDP_RECEIVE_WINDOW_MS
import com.garan.tesnav.ui.LaneGuidanceView
import com.garan.tesnav.ui.AmapAutoDisplayPresenter
import com.garan.tesnav.search.AddressCandidate
import com.garan.tesnav.search.AddressLookupController
import com.garan.tesnav.search.AddressLookupView
import com.garan.tesnav.search.AddressPoint
import com.garan.tesnav.search.AddressSuggestion
import com.garan.tesnav.search.AmapAddressLookupGateway
import com.garan.tesnav.search.LocationFailure
import com.garan.tesnav.search.selectLocationFailure
import com.garan.tesnav.service.NavigationForegroundService
import com.garan.tesnav.service.NavigationDataSource
import com.garan.tesnav.service.NavigationSourcePolicy
import com.garan.tesnav.ui.NavigationStateDialog
import com.garan.tesnav.ui.NavigationSourceMenu
import com.garan.tesnav.ui.AmapAutoActions
import com.garan.tesnav.ui.SettingsDialog
import com.garan.tesnav.config.AmapConfiguration
import com.garan.tesnav.search.SearchHistory
import com.garan.tesnav.search.SearchHistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/** Normal map page. Navigation is displayed by [NavigationActivity]. */
class MainActivity : Activity(), AddressLookupView {
    private lateinit var mapView: MapView
    private lateinit var planButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var debugButton: ImageButton
    private lateinit var sourceButton: Button
    private lateinit var destinationInput: EditText
    private lateinit var searchButton: Button
    private lateinit var searchProgress: ProgressBar
    private lateinit var searchStatus: TextView
    private lateinit var suggestionList: ListView
    private lateinit var suggestionAdapter: ArrayAdapter<String>
    private lateinit var currentAddressText: TextView
    private lateinit var searchPanel: LinearLayout
    private lateinit var autoNavigationSummary: TextView
    private lateinit var autoLaneGuidance: LaneGuidanceView
    private val feedbackDisplayBudgetMs = BuildConfig.NAV_ASSIST_V2_INTERVAL_MS
        .coerceIn(NavAssistV2Protocol.MIN_INTERVAL_MS, Long.MAX_VALUE - NAVASSIST_UDP_RECEIVE_WINDOW_MS) +
        NAVASSIST_UDP_RECEIVE_WINDOW_MS
    private lateinit var addressLookupController: AddressLookupController
    private lateinit var clearHistoryButton: Button
    private val history by lazy { SearchHistory.from(this) }
    private var historyRows: List<SearchHistoryEntry> = emptyList()
    private var showingHistory = false

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtimeService: NavigationForegroundService? = null
    private var bindRequested = false
    private var stateJob: Job? = null
    private var autoGuidanceExpiryJob: Job? = null
    private var sourceJob: Job? = null
    private var locationStatusJob: Job? = null
    private var suggestionJob: Job? = null
    private var currentState = NavigationState()
    private var previousMode: NavigationMode? = null
    private var destination: LatLng? = null
    private var destinationMarker: Marker? = null
    private var navigationPageOpening = false
    private var autoOpenedRouteRevision: Long? = null
    private var autoLandscapePageOpened = false
    private var initialCameraPositioned = false
    private var mapState = MapState.FOLLOW
    private var lastMapInteractionAt = 0L
    private var lastLocationPoint: AddressPoint? = null
    private var suggestions: List<AddressSuggestion> = emptyList()
    private var suppressTextChanges = false

    private val runtimeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as NavigationForegroundService.LocalBinder).getService()
            runtimeService = service
            service.refreshAMapConfiguration()?.let(::toast)
            debugButton.isEnabled = true
            observeRuntime(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cancelRuntimeObservation()
            runtimeService = null
            bindRequested = false
            debugButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoLandscapePageOpened = savedInstanceState?.getBoolean("autoLandscapePageOpened") == true &&
            resources.configuration.screenWidthDp >= 600 &&
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        if (!AmapConfiguration.prepare(applicationContext)) {
            startActivity(Intent(this, AmapKeyActivity::class.java))
            finish()
            return
        }

        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
        AMapLocationClient.updatePrivacyAgree(applicationContext, true)
        ServiceSettings.updatePrivacyShow(applicationContext, true, true)
        ServiceSettings.updatePrivacyAgree(applicationContext, true)

        mapView = MapView(this)
        addressLookupController = AddressLookupController(
            gateway = AmapAddressLookupGateway(applicationContext),
            view = this,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
        )
        createControls()
        setContentView(createRootView())
        mapView.onCreate(savedInstanceState)
        configureMap()
        configureActions()
        showRecentSearches()

        if (hasLocationPermission()) {
            enableMapLocation()
            startRuntimeService()
            requestRemainingPermissions()
        } else {
            requestPermissions(LOCATION_PERMISSIONS, LOCATION_PERMISSION_REQUEST)
        }
    }

    private fun createControls() {
        planButton = actionButton("导航到这里").apply { visibility = View.GONE }
        settingsButton = floatingIconButton(R.drawable.ic_settings, "打开设置")
        debugButton = floatingIconButton(R.drawable.ic_bug_report, "查看 NavigationState").apply {
            isEnabled = false
        }
        sourceButton = actionButton(
            NavigationSourcePolicy.buttonText(NavigationDataSource.AMAP_API, BuildConfig.EXTENDED_NAV_SOURCES),
        ).apply { textSize = 14f }
        destinationInput = EditText(this).apply {
            hint = "输入目的地地址或地点"
            setSingleLine(true)
            textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedRect(Color.WHITE, Color.rgb(207, 216, 220))
        }
        searchButton = actionButton("搜索").apply { textSize = 15f }
        searchProgress = ProgressBar(this).apply { visibility = View.GONE }
        searchStatus = TextView(this).apply {
            text = "也可长按地图选择目的地"
            textSize = 13f
            setTextColor(Color.rgb(84, 110, 122))
        }
        clearHistoryButton = Button(this).apply {
            text = "清空历史"
            textSize = 13f
            visibility = View.GONE
            setOnClickListener { confirmClearHistory() }
        }
        suggestionAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        suggestionList = ListView(this).apply {
            adapter = suggestionAdapter
            visibility = View.GONE
            dividerHeight = dp(1)
        }
        currentAddressText = TextView(this).apply {
            text = "当前位置：定位中…"
            textSize = 14f
            setTextColor(Color.rgb(38, 50, 56))
        }
        autoNavigationSummary = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(0, 105, 92))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            visibility = View.GONE
            contentDescription = "高德车机导航摘要，点击查看导航详情"
            setOnClickListener { openNavigationPage() }
        }
        autoLaneGuidance = LaneGuidanceView(this, feedbackDisplayBudgetMs)
        searchPanel = createSearchPanel()
    }

    private fun createSearchPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        elevation = dp(10).toFloat()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedRect(Color.argb(242, 255, 255, 255), Color.rgb(207, 216, 220))
        addView(autoNavigationSummary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(autoLaneGuidance, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(currentAddressText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(destinationInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchProgress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
            addView(searchButton, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(searchStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        addView(clearHistoryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            gravity = Gravity.END
        })
        addView(suggestionList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MAX_SUGGESTION_LIST_HEIGHT_DP)).apply {
            topMargin = dp(4)
        })
    }

    private fun createRootView(): FrameLayout = FrameLayout(this).apply {
        addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(searchPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            marginStart = dp(12)
            marginEnd = dp(12)
            topMargin = dp(12)
        })
        addView(planButton, FrameLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(32)
        })
        addView(settingsButton, leftButtonParams(stackLevel = 2))
        addView(debugButton, leftButtonParams(stackLevel = 3))
        addView(sourceButton, FrameLayout.LayoutParams(dp(132), dp(52)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = dp(16)
            bottomMargin = dp(16)
        })
    }

    private fun leftButtonParams(stackLevel: Int) = FrameLayout.LayoutParams(dp(52), dp(52)).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        marginStart = dp(16)
        bottomMargin = dp(16 + stackLevel * 64)
    }

    private fun configureMap() {
        mapView.map.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = true
            isCompassEnabled = false
            isScaleControlsEnabled = false
            isIndoorSwitchEnabled = false
            isTiltGesturesEnabled = false
        }
        mapView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))
        mapView.map.setOnMapLongClickListener(::selectDestination)
        mapView.map.setOnMapTouchListener { event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                lastMapInteractionAt = SystemClock.elapsedRealtime()
                if (mapState != MapState.BROWSE) {
                    mapState = MapState.BROWSE
                    applyLocationMode(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
                }
            }
        }
        mapView.map.setOnMyLocationChangeListener { location ->
            locationFailure(location)?.let { failure ->
                lastLocationPoint = null
                showLocationFailure(
                    "定位失败（${failure.errorCode}）：${failure.errorInfo}",
                )
                return@setOnMyLocationChangeListener
            }
            val point = AddressPoint(location.latitude, location.longitude)
            if (isValidLocation(point)) {
                locationStatusJob?.cancel()
                locationStatusJob = null
                lastLocationPoint = point
                addressLookupController.updateLocation(point)
            } else {
                lastLocationPoint = null
                showLocationFailure("尚无有效定位")
                return@setOnMyLocationChangeListener
            }

            if (!initialCameraPositioned) {
                initialCameraPositioned = true
                mapView.map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude),
                        INITIAL_ZOOM,
                    ),
                )
            }

            if (mapState == MapState.BROWSE &&
                location.hasSpeed() && location.speed > MOVING_SPEED_MPS &&
                SystemClock.elapsedRealtime() - lastMapInteractionAt >= BROWSE_TIMEOUT_MS
            ) {
                mapState = MapState.FOLLOW
                applyLocationMode(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
            }
        }
    }

    private fun configureActions() {
        sourceButton.setOnClickListener {
            val service = runtimeService ?: return@setOnClickListener
            if (BuildConfig.EXTENDED_NAV_SOURCES) {
                NavigationSourceMenu.show(
                    this,
                    sourceButton,
                    service.navigationDataSource.value,
                    BuildConfig.EXTENDED_NAV_SOURCES,
                ) { source ->
                    service.selectNavigationDataSource(source)?.let(::toast) ?: run {
                        if (source == NavigationDataSource.AMAP_AUTO) {
                            openNavigationPage()
                        }
                    }
                }
            } else if (service.toggleAmapNavigationDataSource() == NavigationDataSource.AMAP_AUTO) {
                openNavigationPage()
            }
        }
        planButton.setOnClickListener { destination?.let(::openNavigationPage) }
        searchButton.setOnClickListener(::searchDestinationAddress)
        destinationInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDestinationAddress()
                true
            } else {
                false
            }
        }
        destinationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(value: Editable?) {
                if (suppressTextChanges || !canSearch()) return
                clearDestination()
                suggestionJob?.cancel()
                val query = value?.toString().orEmpty()
                if (query.isNotBlank()) {
                    showingHistory = false
                    suggestions = emptyList()
                    suggestionAdapter.clear()
                    suggestionList.visibility = View.GONE
                    clearHistoryButton.visibility = View.GONE
                }
                if (query.trim().length < MIN_SUGGESTION_QUERY_LENGTH) {
                    addressLookupController.suggestDestinations(query, lastLocationPoint)
                    return
                }
                suggestionJob = activityScope.launch {
                    delay(SUGGESTION_DEBOUNCE_MS)
                    addressLookupController.suggestDestinations(query, lastLocationPoint)
                }
            }
        })
        suggestionList.setOnItemClickListener { _, _, position, _ ->
            if (showingHistory) {
                historyRows.getOrNull(position)?.let { entry ->
                    if (entry.destination != null) onDestinationFound(entry.destination) else {
                        suppressTextChanges = true
                        destinationInput.setText(entry.query)
                        suppressTextChanges = false
                        searchDestinationAddress()
                    }
                }
            } else suggestions.getOrNull(position)?.let(addressLookupController::selectSuggestion)
        }
        suggestionList.setOnItemLongClickListener { _, _, position, _ ->
            val entry = if (showingHistory) historyRows.getOrNull(position) else null
            if (entry == null) false else {
                android.app.AlertDialog.Builder(this).setTitle("删除这条记录？").setMessage(entry.query)
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        history.remove(entry)
                        showRecentSearches()
                    }.show()
                true
            }
        }
        destinationInput.setOnFocusChangeListener { _, focused -> if (focused) showRecentSearches() }
        settingsButton.setOnClickListener {
            android.app.AlertDialog.Builder(this).setTitle("${applicationInfo.loadLabel(packageManager)} 设置")
                .setItems(arrayOf("高德 Key 与配置指南", "清空搜索历史", "连接与导航设置")) { _, index ->
                    when (index) {
                        0 -> if (runtimeService?.canEditAmapKey() == true) {
                            startActivity(Intent(this, AmapKeyActivity::class.java))
                        } else toast("请先结束当前导航再修改 Key")
                        1 -> confirmClearHistory()
                        2 -> runtimeService?.let { SettingsDialog(this, activityScope, it).show() }
                            ?: toast("后台导航服务尚未连接")
                    }
                }.setNegativeButton("关闭", null).show()
        }
        debugButton.setOnClickListener {
            runtimeService?.let { service ->
                NavigationStateDialog(this, activityScope, service).show()
            }
        }
        if (BuildConfig.DEBUG) debugButton.setOnLongClickListener {
            startActivity(Intent(this, com.garan.tesnav.ui.NavigationMapPreviewActivity::class.java))
            true
        }
    }

    private fun searchDestinationAddress(@Suppress("UNUSED_PARAMETER") ignored: View? = null) {
        if (!canSearch()) {
            toast("请先结束当前导航")
            return
        }
        val query = destinationInput.text?.toString().orEmpty()
        history.recordQuery(query)
        addressLookupController.searchDestination(query, lastLocationPoint)
    }

    override fun onDestinationSearchStarted(query: String) = withLiveUi {
        showingHistory = false
        clearHistoryButton.visibility = View.GONE
        searchButton.isEnabled = false
        searchProgress.visibility = View.VISIBLE
        searchStatus.setTextColor(Color.rgb(84, 110, 122))
        searchStatus.text = "正在搜索：$query"
    }

    override fun onDestinationSuggestions(suggestions: List<AddressSuggestion>) = withLiveUi {
        if (suggestions.isEmpty() && destinationInput.text.isNullOrBlank()) {
            showRecentSearches()
            return@withLiveUi
        }
        showingHistory = false
        clearHistoryButton.visibility = View.GONE
        this.suggestions = suggestions
        suggestionAdapter.clear()
        suggestionAdapter.addAll(suggestions.map(::suggestionLabel))
        suggestionAdapter.notifyDataSetChanged()
        suggestionList.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
        if (suggestions.isNotEmpty()) {
            finishDestinationSearch()
            searchStatus.setTextColor(Color.rgb(0, 105, 92))
            searchStatus.text = "请选择目的地（${suggestions.size} 个候选）"
        }
    }

    override fun onDestinationFound(candidate: AddressCandidate) = withLiveUi {
        finishDestinationSearch()
        if (!canSearch()) {
            onDestinationSearchFailed("当前导航已启动，请先结束导航")
            return@withLiveUi
        }
        val point = LatLng(candidate.latitude, candidate.longitude)
        history.recordDestination(candidate)
        suppressTextChanges = true
        destinationInput.setText(candidate.name)
        destinationInput.setSelection(destinationInput.text.length)
        suppressTextChanges = false
        onDestinationSuggestions(emptyList())
        selectDestination(point)
        mapView.map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, DESTINATION_ZOOM))
        searchStatus.setTextColor(Color.rgb(0, 105, 92))
        searchStatus.text = "已找到：${candidate.formattedAddress}，请点击“导航到这里”确认"
        hideKeyboard()
    }

    override fun onDestinationSearchFailed(message: String) = withLiveUi {
        finishDestinationSearch()
        searchStatus.setTextColor(Color.rgb(198, 40, 40))
        searchStatus.text = message
    }

    override fun onCurrentAddressLoading() = withLiveUi {
        currentAddressText.setTextColor(Color.rgb(84, 110, 122))
        currentAddressText.text = lastLocationPoint?.let {
            "当前位置：正在解析地址…（${formatCoordinate(it)}）"
        } ?: "当前位置：定位中…"
    }

    override fun onCurrentAddressResolved(address: String) = withLiveUi {
        currentAddressText.setTextColor(Color.rgb(38, 50, 56))
        currentAddressText.text = "当前位置：$address"
    }

    override fun onCurrentAddressFailed(message: String) = withLiveUi {
        currentAddressText.setTextColor(Color.rgb(198, 40, 40))
        currentAddressText.text = lastLocationPoint?.let {
            "当前位置：地址暂不可用（${formatCoordinate(it)}）\n$message"
        } ?: "当前位置：$message"
    }

    private fun finishDestinationSearch() {
        searchButton.isEnabled = true
        searchProgress.visibility = View.GONE
    }

    private fun showRecentSearches() {
        if (!destinationInput.text.isNullOrBlank() || !canSearch()) return
        showingHistory = true
        historyRows = history.entries()
        suggestions = emptyList()
        suggestionAdapter.clear()
        suggestionAdapter.addAll(historyRows.map { entry ->
            entry.destination?.let { "⌖ ${entry.query}\n${it.formattedAddress}" } ?: "↺ ${entry.query}"
        })
        suggestionAdapter.notifyDataSetChanged()
        suggestionList.visibility = if (historyRows.isEmpty()) View.GONE else View.VISIBLE
        clearHistoryButton.visibility = if (historyRows.isEmpty()) View.GONE else View.VISIBLE
        searchStatus.text = if (historyRows.isEmpty()) "搜索地点后会保留历史；也可长按地图选择目的地"
                            else "最近搜索 · 点击重选，长按删除"
    }

    private fun confirmClearHistory() {
        android.app.AlertDialog.Builder(this).setTitle("清空搜索历史？").setMessage("仅删除本机的搜索记录。")
            .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                history.clear()
                showRecentSearches()
            }.show()
    }

    private fun suggestionLabel(suggestion: AddressSuggestion): String {
        val detail = listOf(suggestion.district, suggestion.address)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" · ")
        return if (detail.isBlank()) suggestion.name else "${suggestion.name}\n$detail"
    }

    private fun withLiveUi(action: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) action()
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(destinationInput.windowToken, 0)
        destinationInput.clearFocus()
    }

    private fun formatCoordinate(point: AddressPoint): String =
        String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)

    private fun isValidLocation(point: AddressPoint): Boolean =
        point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
            (point.latitude != 0.0 || point.longitude != 0.0)

    private fun locationFailure(location: Location): LocationFailure? {
        val extrasErrorCode = runCatching {
            location.extras?.getInt(MyLocationStyle.ERROR_CODE, 0) ?: 0
        }.getOrDefault(0)
        val extrasErrorInfo = runCatching {
            location.extras?.getString(MyLocationStyle.ERROR_INFO)
        }.getOrNull()
        val amapLocation = location as? AMapLocation
        return selectLocationFailure(
            extrasErrorCode = extrasErrorCode,
            extrasErrorInfo = extrasErrorInfo,
            subtypeErrorCode = amapLocation?.errorCode ?: 0,
            subtypeErrorInfo = amapLocation?.errorInfo,
        )
    }

    private fun showLocationFailure(message: String) {
        locationStatusJob?.cancel()
        locationStatusJob = null
        addressLookupController.locationFailed(message)
    }

    private fun scheduleLocationStatusTimeout() {
        locationStatusJob?.cancel()
        locationStatusJob = activityScope.launch {
            delay(LOCATION_WAIT_TIMEOUT_MS)
            if (lastLocationPoint == null) {
                addressLookupController.locationFailed("尚无有效定位，请到开阔区域或检查定位设置")
            }
        }
    }

    private fun selectDestination(point: LatLng) {
        if (!canSearch()) {
            toast("请先结束当前导航")
            return
        }
        destination = point
        destinationMarker?.remove()
        destinationMarker = mapView.map.addMarker(MarkerOptions().position(point).title("目的地"))
        planButton.visibility = View.VISIBLE
    }

    private fun openNavigationPage(target: LatLng? = null) {
        if (BuildConfig.EXTENDED_NAV_SOURCES &&
            runtimeService?.navigationDataSource?.value == NavigationDataSource.AMAP_AUTO && target != null
        ) {
            if (runtimeService?.navigateAmapAutoDestination(destinationInput.text.toString(), target.latitude, target.longitude) == true) {
                toast("目的地已发送，请在高德车机确认导航")
            } else {
                toast("未能打开高德车机，请确认已安装车机版")
            }
            return
        }
        if (navigationPageOpening) return
        if (BuildConfig.EXTENDED_NAV_SOURCES &&
            runtimeService?.navigationDataSource?.value == NavigationDataSource.AMAP_AUTO &&
            resources.configuration.screenWidthDp >= 600 &&
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        ) autoLandscapePageOpened = true
        navigationPageOpening = true
        startActivity(
            Intent(this, NavigationActivity::class.java).apply {
                target?.let {
                    putExtra(NavigationActivity.EXTRA_DESTINATION_LATITUDE, it.latitude)
                    putExtra(NavigationActivity.EXTRA_DESTINATION_LONGITUDE, it.longitude)
                }
            },
        )
    }

    private fun observeRuntime(service: NavigationForegroundService) {
        cancelRuntimeObservation()
        stateJob = activityScope.launch {
            combine(service.selectedNavigationStateFlow,
                service.oemVehicleLaneState.withDisplayExpiry(feedbackDisplayBudgetMs, SystemClock::elapsedRealtime),
                service.amapAutoBroadcasts,
            ) { state, oem, broadcasts -> Triple(state, oem, broadcasts) }.collect { (state, oem, broadcasts) ->
                if (state.navigationProvider != service.navigationDataSource.value.name) return@collect
                currentState = state
                autoGuidanceExpiryJob?.cancel()
                val apiSelected = state.navigationProvider == NavigationDataSource.AMAP_API.name
                val autoSelected = BuildConfig.EXTENDED_NAV_SOURCES &&
                    state.navigationProvider == NavigationDataSource.AMAP_AUTO.name
                if (!autoSelected || !state.routePlanned) autoOpenedRouteRevision = null
                if (!autoSelected) autoLandscapePageOpened = false
                autoNavigationSummary.visibility = if (autoSelected) View.VISIBLE else View.GONE
                if (autoSelected) {
                    val fresh = state.guidanceReceivedElapsedMs?.let { SystemClock.elapsedRealtime() - it in 0..3_000 } == true
                    autoNavigationSummary.text = if (state.routePlanned && fresh)
                        "车机导航进行中 · 点击打开导航地图" else autoNavigationSummaryText(state)
                    if (state.routePlanned && fresh) autoLaneGuidance.render(state, oem)
                    else autoLaneGuidance.visibility = View.GONE
                    state.guidanceReceivedElapsedMs?.takeIf { state.routePlanned && fresh }?.let { observed ->
                        autoGuidanceExpiryJob = activityScope.launch {
                            delay((3_001L - (SystemClock.elapsedRealtime() - observed)).coerceAtLeast(1L))
                            autoNavigationSummary.text = autoNavigationSummaryText(state)
                            autoLaneGuidance.visibility = View.GONE
                        }
                    }
                } else {
                    autoLaneGuidance.visibility = View.GONE
                }
                if (apiSelected && state.navigationMode == NavigationMode.IDLE &&
                    previousMode != null && previousMode != NavigationMode.IDLE
                ) {
                    clearDestination()
                } else if (apiSelected && !navigationPageOpening) {
                    if (state.navigationMode != NavigationMode.IDLE) openNavigationPage()
                } else if (autoSelected && !navigationPageOpening && !autoLandscapePageOpened &&
                    resources.configuration.screenWidthDp >= 600 &&
                    resources.configuration.screenWidthDp > resources.configuration.screenHeightDp) {
                    if (state.routePlanned) autoOpenedRouteRevision = state.routeRevision
                    openNavigationPage()
                } else if (autoSelected && !navigationPageOpening && state.routePlanned &&
                    autoOpenedRouteRevision != state.routeRevision &&
                    state.guidanceReceivedElapsedMs?.let { SystemClock.elapsedRealtime() - it in 0..3_000 } == true) {
                    autoOpenedRouteRevision = state.routeRevision
                    openNavigationPage()
                }
                previousMode = state.navigationMode
            }
        }
        sourceJob = activityScope.launch {
            service.navigationDataSource.collect { source ->
                sourceButton.text = NavigationSourcePolicy.buttonText(source, BuildConfig.EXTENDED_NAV_SOURCES)
                if (BuildConfig.EXTENDED_NAV_SOURCES) {
                    planButton.text = if (source == NavigationDataSource.AMAP_AUTO) "发送到高德车机" else "导航到这里"
                }
            }
        }
    }

    private fun autoNavigationSummaryText(state: NavigationState): String {
        if (!state.routePlanned || state.navigationMode == NavigationMode.IDLE) return "高德车机 · 等待导航"
        val fresh = state.guidanceReceivedElapsedMs?.let { SystemClock.elapsedRealtime() - it in 0..3_000 } == true
        if (!fresh) return "高德车机 · 导航数据已过期，等待更新"
        val action = when (state.maneuver) {
            NavigationManeuver.TURN_LEFT, NavigationManeuver.SHARP_LEFT -> "左转"
            NavigationManeuver.TURN_RIGHT, NavigationManeuver.SHARP_RIGHT -> "右转"
            NavigationManeuver.SLIGHT_LEFT, NavigationManeuver.KEEP_LEFT, NavigationManeuver.MERGE_LEFT -> "靠左行驶"
            NavigationManeuver.SLIGHT_RIGHT, NavigationManeuver.KEEP_RIGHT, NavigationManeuver.MERGE_RIGHT -> "靠右行驶"
            NavigationManeuver.EXIT_LEFT, NavigationManeuver.RAMP_LEFT -> "左侧匝道"
            NavigationManeuver.EXIT_RIGHT, NavigationManeuver.RAMP_RIGHT -> "右侧匝道"
            NavigationManeuver.U_TURN_LEFT, NavigationManeuver.U_TURN_RIGHT -> "掉头"
            NavigationManeuver.STRAIGHT -> "直行"
            NavigationManeuver.ROUNDABOUT -> "进入环岛"
            NavigationManeuver.DESTINATION -> "到达目的地"
            else -> "按导航行驶"
        }
        val next = listOfNotNull(state.nextTurnDistanceMeters?.let { "$it 米后" }, action,
            state.nextRoad?.takeIf(String::isNotBlank)).joinToString(" · ")
        val trip = listOfNotNull(state.routeRemainDistanceMeters?.let {
            if (it >= 1000) String.format(Locale.CHINA, "剩余 %.1f 公里", it / 1000.0) else "剩余 $it 米"
        }, state.routeRemainTimeSeconds?.let { "约 ${(it.toLong() + 59) / 60} 分钟" }).joinToString(" · ")
        val light = state.trafficLight?.takeIf { System.currentTimeMillis() - it.observedAtMs in 0..3_000 }?.let {
            val color = when (it.status) { 2 -> "红灯"; 3 -> "绿灯"; 4 -> "黄灯"; else -> "信号灯" }
            val direction = when (it.direction) { 1 -> "左转"; 2 -> "右转"; 3 -> "掉头"; 4 -> "直行"; else -> "方向未知" }
            val elapsedSeconds = ((System.currentTimeMillis() - it.observedAtMs) / 1000L).toInt()
            "$direction · $color${it.countdownSeconds?.let { seconds -> " ${maxOf(0, seconds - elapsedSeconds)} 秒" }.orEmpty()}"
        } ?: "红绿灯：暂无有效数据"
        return listOfNotNull("高德车机 · 导航中", state.currentRoad?.takeIf(String::isNotBlank), next,
            trip.takeIf(String::isNotBlank), light).joinToString("\n")
    }

    private fun canSearch(): Boolean = runtimeService?.let { service ->
        NavigationSourcePolicy.canSearch(service.navigationDataSource.value, service.stateStore.state.value.navigationMode)
    } ?: (currentState.navigationMode == NavigationMode.IDLE)

    private fun clearDestination() {
        destination = null
        destinationMarker?.remove()
        destinationMarker = null
        planButton.visibility = View.GONE
    }

    private fun enableMapLocation() {
        applyLocationMode(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
        mapView.map.isMyLocationEnabled = true
        scheduleLocationStatusTimeout()
    }

    private fun applyLocationMode(type: Int) {
        mapView.map.myLocationStyle = MyLocationStyle()
            .myLocationType(type)
            .interval(LOCATION_INTERVAL_MS)
    }

    private fun startRuntimeService() {
        NavigationForegroundService.start(applicationContext)
        if (window.decorView.isAttachedToWindow) bindRuntimeService()
    }

    private fun bindRuntimeService() {
        if (bindRequested || !hasLocationPermission()) return
        bindRequested = bindService(
            Intent(this, NavigationForegroundService::class.java),
            runtimeConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun cancelRuntimeObservation() {
        autoGuidanceExpiryJob?.cancel()
        autoGuidanceExpiryJob = null
        stateJob?.cancel()
        stateJob = null
        sourceJob?.cancel()
        sourceJob = null
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestRemainingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            return
        }
        requestBackgroundLocationPermission()
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), BACKGROUND_LOCATION_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> if (hasLocationPermission()) {
                enableMapLocation()
                startRuntimeService()
                requestRemainingPermissions()
            } else {
                showLocationFailure("定位权限未授予")
            }
            NOTIFICATION_PERMISSION_REQUEST -> requestBackgroundLocationPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) bindRuntimeService()
    }

    override fun onResume() {
        super.onResume()
        if (!::mapView.isInitialized) return
        val returnedFromNavigation = navigationPageOpening
        navigationPageOpening = false
        if (returnedFromNavigation) clearDestination()
        mapView.onResume()
        mapView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        cancelRuntimeObservation()
        if (bindRequested) unbindService(runtimeConnection)
        runtimeService = null
        bindRequested = false
        if (::debugButton.isInitialized) debugButton.isEnabled = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("autoLandscapePageOpened", autoLandscapePageOpened)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        suggestionJob?.cancel()
        if (::addressLookupController.isInitialized) addressLookupController.close()
        activityScope.cancel()
        if (::mapView.isInitialized) mapView.onDestroy()
        super.onDestroy()
    }

    private fun actionButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(BRAND_BLUE)
    }

    private fun floatingIconButton(icon: Int, description: String): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        setColorFilter(Color.rgb(38, 50, 56))
        setPadding(dp(13), dp(13), dp(13), dp(13))
        elevation = dp(10).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(1), Color.rgb(207, 216, 220))
        }
    }

    private fun roundedRect(fillColor: Int, strokeColor: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(fillColor)
        setStroke(dp(1), strokeColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val LOCATION_PERMISSION_REQUEST = 1
        const val NOTIFICATION_PERMISSION_REQUEST = 2
        const val BACKGROUND_LOCATION_PERMISSION_REQUEST = 3
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val BRAND_BLUE = Color.rgb(0, 120, 255)
        const val INITIAL_ZOOM = 14f
        const val DESTINATION_ZOOM = 16f
        const val MOVING_SPEED_MPS = 1f
        const val BROWSE_TIMEOUT_MS = 15_000L
        const val LOCATION_INTERVAL_MS = 1_000L
        const val LOCATION_WAIT_TIMEOUT_MS = 12_000L
        const val SUGGESTION_DEBOUNCE_MS = 300L
        const val MIN_SUGGESTION_QUERY_LENGTH = 2
        const val MAX_SUGGESTION_LIST_HEIGHT_DP = 280
    }

    private enum class MapState { FOLLOW, BROWSE }
}
