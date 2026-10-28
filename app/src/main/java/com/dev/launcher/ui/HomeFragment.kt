package com.dev.launcher.ui

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Typeface
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.dev.launcher.FontManager
import com.dev.launcher.MainViewModel
import com.dev.launcher.R
import com.dev.launcher.data.AppModel
import com.dev.launcher.data.Constants
import com.dev.launcher.data.Prefs
import com.dev.launcher.databinding.FragmentHomeBinding
import com.dev.launcher.ui.HomeMediaControllerFragment
import com.dev.launcher.helper.appUsagePermissionGranted
import com.dev.launcher.helper.dpToPx
import com.dev.launcher.helper.expandNotificationDrawer
import com.dev.launcher.helper.getChangedAppTheme
import com.dev.launcher.helper.getUserHandleFromString
import com.dev.launcher.helper.isPackageInstalled
import com.dev.launcher.helper.openAlarmApp
import com.dev.launcher.helper.openCalendar
import com.dev.launcher.helper.openCameraApp
import com.dev.launcher.helper.openDialerApp
import com.dev.launcher.helper.openSearch
import com.dev.launcher.helper.setPlainWallpaperByTheme
import com.dev.launcher.helper.showToast
import com.dev.launcher.listener.OnSwipeTouchListener
import com.dev.launcher.listener.ViewSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        applyClockTransform()
        FontManager.applyToView(view, requireContext())

        // attach the media controller fragment into the container
        if (childFragmentManager.findFragmentById(R.id.media_container) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.media_container, HomeMediaControllerFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen(false)
        viewModel.isSlauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
        applyClockTransform()
        FontManager.applyToView(view, requireContext())
    }

    private var isClockEditMode = false
    private var isSnapToGrid = false
    private var currentClockScale = 1.0f
    private var currentClockX = 0f
    private var currentClockY = 0f

    override fun onClick(view: View) {
        if (isClockEditMode) return
        when (view.id) {
            R.id.lock -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()

            else -> {
                try { 
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.clock, R.id.date -> {
                showClockOptionsDialog()
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isSlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isSlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.visibility == View.VISIBLE) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()


        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        (binding.dateTimeLayout.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = horizontalGravity or Gravity.TOP
            binding.dateTimeLayout.layoutParams = lp
        }
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

        FontManager.load(requireContext())?.let { tf ->
            binding.clock.typeface = tf
            try { binding.clock.paint.typeface = tf } catch (_: Exception) {}
            binding.clock.postInvalidate()
            binding.date.typeface = tf
            try { binding.date.paint.typeface = tf } catch (_: Exception) {}
            binding.date.postInvalidate()
        }

        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
        applyClockTransform()
    }

    private fun applyClockTransform() {
        if (!isAdded || _binding == null) return
        binding.dateTimeLayout.translationX = prefs.clockOffsetX
        binding.dateTimeLayout.translationY = prefs.clockOffsetY
        val scale = prefs.clockScale.coerceIn(0.5f, 2.5f)
        binding.dateTimeLayout.scaleX = scale
        binding.dateTimeLayout.scaleY = scale
        val updatePivot = {
            if (isAdded && _binding != null) {
                binding.dateTimeLayout.pivotX = when (prefs.homeAlignment) {
                    Gravity.END -> binding.dateTimeLayout.width.toFloat().takeIf { it > 0 } ?: 0f
                    Gravity.CENTER, Gravity.CENTER_HORIZONTAL -> (binding.dateTimeLayout.width / 2f).takeIf { it > 0 } ?: 0f
                    else -> 0f
                }
                binding.dateTimeLayout.pivotY = 0f
            }
        }
        if (binding.dateTimeLayout.width > 0) {
            updatePivot()
        } else {
            binding.dateTimeLayout.post { updatePivot() }
        }
    }

    private fun showClockOptionsDialog() {
        val options = arrayOf(
            getString(R.string.move_resize_clock),
            getString(R.string.change_clock_app),
            getString(R.string.reset_clock_layout)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clock_options)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> enterClockEditMode()
                    1 -> {
                        showAppList(Constants.FLAG_SET_CLOCK_APP)
                        prefs.clockAppPackage = ""
                        prefs.clockAppClassName = ""
                        prefs.clockAppUser = ""
                    }
                    2 -> {
                        prefs.clockOffsetX = 0f
                        prefs.clockOffsetY = 0f
                        prefs.clockScale = 1.0f
                        applyClockTransform()
                        requireContext().showToast(R.string.clock_layout_reset)
                    }
                }
            }
            .show()
    }

    private fun enterClockEditMode() {
        isClockEditMode = true
        isSnapToGrid = prefs.widgetSnapGrid
        currentClockScale = prefs.clockScale.coerceIn(0.5f, 2.5f)
        currentClockX = prefs.clockOffsetX
        currentClockY = prefs.clockOffsetY

        binding.clockEditBar.visibility = View.VISIBLE
        updateClockEditDisplay()
        updateClockSnapButton()

        binding.dateTimeLayout.setBackgroundResource(R.drawable.rounded_rect_shade_color)

        val scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentClockScale = (currentClockScale * detector.scaleFactor).coerceIn(0.5f, 2.5f)
                binding.dateTimeLayout.scaleX = currentClockScale
                binding.dateTimeLayout.scaleY = currentClockScale
                updateClockEditDisplay()
                return true
            }
        })

        var dX = 0f
        var dY = 0f
        val gridSize = 20.dpToPx().toFloat()
        val dragTouchListener = View.OnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = binding.dateTimeLayout.translationX - event.rawX
                    dY = binding.dateTimeLayout.translationY - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val rawTargetX = event.rawX + dX
                        val rawTargetY = event.rawY + dY
                        if (isSnapToGrid) {
                            currentClockX = (Math.round(rawTargetX / gridSize) * gridSize)
                            currentClockY = (Math.round(rawTargetY / gridSize) * gridSize)
                        } else {
                            currentClockX = rawTargetX
                            currentClockY = rawTargetY
                        }
                        binding.dateTimeLayout.translationX = currentClockX
                        binding.dateTimeLayout.translationY = currentClockY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isSnapToGrid) {
                        currentClockX = (Math.round(binding.dateTimeLayout.translationX / gridSize) * gridSize)
                        currentClockY = (Math.round(binding.dateTimeLayout.translationY / gridSize) * gridSize)
                        binding.dateTimeLayout.translationX = currentClockX
                        binding.dateTimeLayout.translationY = currentClockY
                    } else {
                        currentClockX = binding.dateTimeLayout.translationX
                        currentClockY = binding.dateTimeLayout.translationY
                    }
                    true
                }
                else -> true
            }
        }

        binding.dateTimeLayout.setOnTouchListener(dragTouchListener)
        binding.clock.setOnTouchListener(dragTouchListener)
        binding.date.setOnTouchListener(dragTouchListener)

        binding.clock.isClickable = false
        binding.clock.isLongClickable = false
        binding.date.isClickable = false
        binding.date.isLongClickable = false

        binding.btnClockSnap.setOnClickListener {
            isSnapToGrid = !isSnapToGrid
            prefs.widgetSnapGrid = isSnapToGrid
            updateClockSnapButton()
            if (isSnapToGrid) {
                currentClockX = (Math.round(currentClockX / gridSize) * gridSize)
                currentClockY = (Math.round(currentClockY / gridSize) * gridSize)
                binding.dateTimeLayout.translationX = currentClockX
                binding.dateTimeLayout.translationY = currentClockY
            }
        }

        binding.btnClockSizeMinus.setOnClickListener {
            currentClockScale = (currentClockScale - 0.1f).coerceIn(0.5f, 2.5f)
            binding.dateTimeLayout.scaleX = currentClockScale
            binding.dateTimeLayout.scaleY = currentClockScale
            updateClockEditDisplay()
        }

        binding.btnClockSizePlus.setOnClickListener {
            currentClockScale = (currentClockScale + 0.1f).coerceIn(0.5f, 2.5f)
            binding.dateTimeLayout.scaleX = currentClockScale
            binding.dateTimeLayout.scaleY = currentClockScale
            updateClockEditDisplay()
        }

        binding.btnClockReset.setOnClickListener {
            currentClockX = 0f
            currentClockY = 0f
            currentClockScale = 1.0f
            binding.dateTimeLayout.translationX = 0f
            binding.dateTimeLayout.translationY = 0f
            binding.dateTimeLayout.scaleX = 1.0f
            binding.dateTimeLayout.scaleY = 1.0f
            updateClockEditDisplay()
        }

        binding.btnClockDone.setOnClickListener {
            exitClockEditMode(true)
        }
    }

    private fun updateClockSnapButton() {
        binding.btnClockSnap.text = if (isSnapToGrid) getString(R.string.snap_on) else getString(R.string.snap_off)
    }

    private fun updateClockEditDisplay() {
        val pct = (currentClockScale * 100).toInt()
        binding.tvClockScaleDisplay.text = "$pct%"
    }

    private fun exitClockEditMode(save: Boolean) {
        isClockEditMode = false
        binding.clockEditBar.visibility = View.GONE
        binding.dateTimeLayout.background = null
        binding.dateTimeLayout.setOnTouchListener(null)
        binding.clock.setOnTouchListener(null)
        binding.date.setOnTouchListener(null)

        binding.clock.isClickable = true
        binding.clock.isLongClickable = true
        binding.date.isClickable = true
        binding.date.isLongClickable = true

        if (save) {
            prefs.clockOffsetX = currentClockX
            prefs.clockOffsetY = currentClockY
            prefs.clockScale = currentClockScale
            prefs.widgetSnapGrid = isSnapToGrid
            requireContext().showToast(R.string.clock_layout_saved)
        }
        applyClockTransform()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE
        FontManager.load(requireContext())?.let { tf ->
            binding.tvScreenTime.typeface = tf
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) return

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp1, prefs.appName1, prefs.appPackage1, prefs.appUser1)) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) return

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp2, prefs.appName2, prefs.appPackage2, prefs.appUser2)) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) return

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp3, prefs.appName3, prefs.appPackage3, prefs.appUser3)) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) return

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp4, prefs.appName4, prefs.appPackage4, prefs.appUser4)) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) return

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp5, prefs.appName5, prefs.appPackage5, prefs.appUser5)) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
    }

    private fun setHomeAppText(textView: TextView, appName: String, packageName: String, userString: String): Boolean {
        val customTf = FontManager.load(requireContext())
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            if (customTf != null) {
                textView.typeface = customTf
                try { textView.paint.typeface = customTf } catch (_: Exception) {}
                textView.postInvalidate()
            } else {
                textView.setTypeface(textView.typeface, Typeface.BOLD)
            }
            return true
        }
        textView.text = ""
        if (customTf != null) {
            textView.typeface = customTf
            try { textView.paint.typeface = customTf } catch (_: Exception) {}
            textView.postInvalidate()
        } else {
            textView.setTypeface(textView.typeface, Typeface.NORMAL)
        }
        return false
    }

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
    }

    private fun homeAppClicked(location: Int) {
        if (prefs.getAppName(location).isEmpty()) showLongPressToast()
        else launchApp(
            prefs.getAppName(location),
            prefs.getAppPackage(location),
            prefs.getAppActivityClassName(location),
            prefs.getAppUser(location)
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel(
                appName,
                null,
                packageName,
                activityClassName,
                false,
                getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun swipeDownAction() {
        when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.SEARCH -> openSearch(requireContext())
            else -> expandNotificationDrawer(requireContext())
        }
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        if (prefs.appPackageSwipeRight.isNotEmpty())
            launchApp(
                prefs.appNameSwipeRight,
                prefs.appPackageSwipeRight,
                prefs.appActivityClassNameRight,
                prefs.appUserSwipeRight
            )
        else openDialerApp(requireContext())
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        if (prefs.appPackageSwipeLeft.isNotEmpty())
            launchApp(
                prefs.appNameSwipeLeft,
                prefs.appPackageSwipeLeft,
                prefs.appActivityClassNameSwipeLeft,
                prefs.appUserSwipeLeft
            )
        else openCameraApp(requireContext())
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        setPlainWallpaperByTheme(requireContext(), changedAppTheme)
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else if (prefs.lockModeOn)
                    lockPhone()
            }

            override fun onClick() {
                super.onClick()
                viewModel.checkForMessages.call()
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}