package com.dev.launcher.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.dev.launcher.BuildConfig
import com.dev.launcher.MainViewModel
import com.dev.launcher.R
import com.dev.launcher.data.Constants
import com.dev.launcher.data.Prefs
import com.dev.launcher.databinding.FragmentSettingsBinding
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.content.IntentFilter
import android.content.DialogInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Typeface
import androidx.activity.result.contract.ActivityResultContracts
import com.dev.launcher.FontManager
import com.dev.launcher.settings.FontSettingsActivity
import java.io.File
import java.io.FileOutputStream
import com.dev.launcher.helper.animateAlpha
import com.dev.launcher.helper.appUsagePermissionGranted
import com.dev.launcher.helper.getColorFromAttr
import com.dev.launcher.helper.isAccessServiceEnabled
import com.dev.launcher.helper.isDarkThemeOn
import com.dev.launcher.helper.isSlauncherDefault
import com.dev.launcher.helper.openAppInfo
import com.dev.launcher.helper.openUrl
import com.dev.launcher.helper.rateApp
import com.dev.launcher.helper.setPlainWallpaper
import com.dev.launcher.helper.shareApp
import com.dev.launcher.helper.showToast
import com.dev.launcher.listener.DeviceAdmin

class SettingsFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var componentName: ComponentName

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val pickFontLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { applyCustomFont(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        viewModel.isSlauncherDefault()

        deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireContext(), DeviceAdmin::class.java)
        checkAdminPermission()

        binding.homeAppsNum.text = prefs.homeAppsNum.toString()
        populateProMessage()
        populateKeyboardText()
        populateScreenTimeOnOff()
        populateLockSettings()
        populateAppThemeText()
        populateFontText()
        populateTextSize()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateSwipeApps()
        populateSwipeDownAction()
        populateAutoOpenApp()
        initClickListeners()
        initObservers()
        // show currently selected media app (if any) and widget enabled state
        val mediaPrefs = requireContext().getSharedPreferences("home_media", Context.MODE_PRIVATE)
        val sel = mediaPrefs.getString("selected_pkg", "") ?: ""
        if (sel.isNotEmpty()) {
            try {
                val pm = requireContext().packageManager
                val ai = pm.getApplicationInfo(sel, 0)
                binding.mediaAppName?.text = pm.getApplicationLabel(ai)
            } catch (e: Exception) {
                binding.mediaAppName?.text = sel
            }
        }

        val enabled = mediaPrefs.getBoolean("enabled", true)
        binding.mediaWidgetEnabledText?.text = if (enabled) getString(R.string.on) else getString(R.string.off)
        binding.mediaWidgetEnabledText?.setOnClickListener {
            val newVal = !mediaPrefs.getBoolean("enabled", true)
            mediaPrefs.edit().putBoolean("enabled", newVal).apply()
            binding.mediaWidgetEnabledText?.text = if (newVal) getString(R.string.on) else getString(R.string.off)
        }

        FontManager.applyToView(view, requireContext())

        binding.enableListenerRow?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onClick(view: View) {
        binding.appsNumSelectLayout.visibility = View.GONE
        binding.dateTimeSelectLayout.visibility = View.GONE
        binding.appThemeSelectLayout.visibility = View.GONE
        binding.swipeDownSelectLayout.visibility = View.GONE
        binding.textSizesLayout.visibility = View.GONE
        if (view.id != R.id.alignmentBottom)
            binding.alignmentSelectLayout.visibility = View.GONE

        when (view.id) {
            R.id.slauncherHiddenApps -> showHiddenApps()
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.toggleLock -> toggleLockMode()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.homeAppsNum -> binding.appsNumSelectLayout.visibility = View.VISIBLE

            R.id.alignment -> binding.alignmentSelectLayout.visibility = View.VISIBLE
            R.id.alignmentLeft -> viewModel.updateHomeAlignment(Gravity.START)
            R.id.alignmentCenter -> viewModel.updateHomeAlignment(Gravity.CENTER)
            R.id.alignmentRight -> viewModel.updateHomeAlignment(Gravity.END)
            R.id.alignmentBottom -> updateHomeBottomAlignment()
            R.id.statusBar -> toggleStatusBar()
            R.id.dateTime -> binding.dateTimeSelectLayout.visibility = View.VISIBLE
            R.id.dateTimeOn -> toggleDateTime(Constants.DateTime.ON)
            R.id.dateTimeOff -> toggleDateTime(Constants.DateTime.OFF)
            R.id.dateOnly -> toggleDateTime(Constants.DateTime.DATE_ONLY)
            R.id.appThemeText -> binding.appThemeSelectLayout.visibility = View.VISIBLE
            R.id.themeLight -> updateTheme(AppCompatDelegate.MODE_NIGHT_NO)
            R.id.themeDark -> updateTheme(AppCompatDelegate.MODE_NIGHT_YES)
            R.id.themeSystem -> updateTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            R.id.fontText -> binding.fontSelectLayout?.visibility = View.VISIBLE
            R.id.fontDefault -> updateFont(Constants.Font.DEFAULT)
            R.id.fontInter -> updateFont(Constants.Font.INTER)
            R.id.fontUbuntu -> updateFont(Constants.Font.UBUNTU)
            R.id.fontEBGaramond -> updateFont(Constants.Font.EBGARAMOND)
            R.id.fontCrimsonPro -> updateFont(Constants.Font.CRIMSONPRO)
            R.id.fontCustom -> {
                if (FontManager.hasCustomFontFile(requireContext())) {
                    updateFont(Constants.Font.CUSTOM)
                } else {
                    pickFontLauncher.launch(arrayOf("*/*"))
                }
            }
            R.id.textSizeValue -> binding.textSizesLayout.visibility = View.VISIBLE
            R.id.actionAccessibility -> openAccessibilityService()
            R.id.closeAccessibility -> toggleAccessibilityVisibility(false)
            R.id.notWorking -> requireContext().openUrl(Constants.URL_DOUBLE_TAP)

            R.id.tvGestures -> binding.flSwipeDown.visibility = View.VISIBLE

            R.id.maxApps0 -> updateHomeAppsNum(0)
            R.id.maxApps1 -> updateHomeAppsNum(1)
            R.id.maxApps2 -> updateHomeAppsNum(2)
            R.id.maxApps3 -> updateHomeAppsNum(3)
            R.id.maxApps4 -> updateHomeAppsNum(4)
            R.id.maxApps5 -> updateHomeAppsNum(5)

            R.id.textSize1 -> updateTextSizeScale(Constants.TextSize.ONE)
            R.id.textSize2 -> updateTextSizeScale(Constants.TextSize.TWO)
            R.id.textSize3 -> updateTextSizeScale(Constants.TextSize.THREE)
            R.id.textSize4 -> updateTextSizeScale(Constants.TextSize.FOUR)
            R.id.textSize5 -> updateTextSizeScale(Constants.TextSize.FIVE)
            R.id.textSize6 -> updateTextSizeScale(Constants.TextSize.SIX)
            R.id.textSize7 -> updateTextSizeScale(Constants.TextSize.SEVEN)
            R.id.textSize8 -> updateTextSizeScale(Constants.TextSize.EIGHT)
            R.id.textSize9 -> updateTextSizeScale(Constants.TextSize.NINE)
            R.id.textSize10 -> updateTextSizeScale(Constants.TextSize.TEN)

            R.id.swipeLeftApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_LEFT_APP)
            R.id.swipeRightApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_RIGHT_APP)
            R.id.swipeDownAction -> binding.swipeDownSelectLayout.visibility = View.VISIBLE
            R.id.notifications -> updateSwipeDownAction(Constants.SwipeDownAction.NOTIFICATIONS)
            R.id.search -> updateSwipeDownAction(Constants.SwipeDownAction.SEARCH)
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.alignment -> {
                prefs.appLabelAlignment = prefs.homeAlignment
                findNavController().navigate(R.id.action_settingsFragment_to_appListFragment)
                requireContext().showToast(getString(R.string.alignment_changed))
            }


            R.id.appThemeText -> {
                binding.appThemeSelectLayout.visibility = View.VISIBLE
                binding.themeSystem.visibility = View.VISIBLE
            }

            R.id.swipeLeftApp -> toggleSwipeLeft()
            R.id.swipeRightApp -> toggleSwipeRight()
            R.id.toggleLock -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return true
    }

    private fun initClickListeners() {
        binding.slauncherHiddenApps.setOnClickListener(this)
        binding.scrollLayout.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        binding.toggleLock.setOnClickListener(this)
        binding.homeAppsNum.setOnClickListener(this)
        binding.screenTimeOnOff.setOnClickListener(this)
        binding.alignment.setOnClickListener(this)
        binding.alignmentLeft.setOnClickListener(this)
        binding.alignmentCenter.setOnClickListener(this)
        binding.alignmentRight.setOnClickListener(this)
        binding.alignmentBottom.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.dateTime.setOnClickListener(this)
        binding.dateTimeOn.setOnClickListener(this)
        binding.dateTimeOff.setOnClickListener(this)
        binding.dateOnly.setOnClickListener(this)
        binding.swipeLeftApp.setOnClickListener(this)
        binding.swipeRightApp.setOnClickListener(this)
        // media toggle row
        val toggleMediaAction = View.OnClickListener {
            val mediaPrefs = requireContext().getSharedPreferences("home_media", Context.MODE_PRIVATE)
            val newVal = !mediaPrefs.getBoolean("enabled", true)
            mediaPrefs.edit().putBoolean("enabled", newVal).apply()
            binding.mediaWidgetEnabledText?.text = if (newVal) getString(R.string.on) else getString(R.string.off)
        }
        binding.mediaToggleRow?.setOnClickListener(toggleMediaAction)
        binding.mediaWidgetEnabledText?.setOnClickListener(toggleMediaAction)

        // choose media row opens chooser
        binding.chooseMediaRow?.setOnClickListener { showMediaChooserDialog() }
        binding.swipeDownAction.setOnClickListener(this)
        binding.search.setOnClickListener(this)
        binding.notifications.setOnClickListener(this)
        binding.appThemeText.setOnClickListener(this)
        binding.themeLight.setOnClickListener(this)
        binding.themeDark.setOnClickListener(this)
        binding.themeSystem.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.actionAccessibility.setOnClickListener(this)
        binding.closeAccessibility.setOnClickListener(this)
        binding.notWorking.setOnClickListener(this)

        binding.fontText?.setOnClickListener(this)
        binding.fontDefault?.setOnClickListener(this)
        binding.fontInter?.setOnClickListener(this)
        binding.fontUbuntu?.setOnClickListener(this)
        binding.fontEBGaramond?.setOnClickListener(this)
        binding.fontCrimsonPro?.setOnClickListener(this)
        binding.fontCustom?.setOnClickListener(this)
        binding.importCustomFontRow?.setOnClickListener {
            pickFontLauncher.launch(arrayOf("*/*"))
        }
        binding.importCustomFontRow?.setOnLongClickListener {
            startActivity(Intent(requireContext(), FontSettingsActivity::class.java))
            true
        }

        binding.autoOpenAppLayout?.setOnClickListener { toggleAutoOpenApp() }
        binding.autoOpenApp?.setOnClickListener { toggleAutoOpenApp() }

        binding.resetClockLayout?.setOnClickListener {
            prefs.clockOffsetX = 0f
            prefs.clockOffsetY = 0f
            prefs.clockScale = 1.0f
            requireContext().showToast(R.string.clock_layout_reset)
        }

        binding.maxApps0.setOnClickListener(this)
        binding.maxApps1.setOnClickListener(this)
        binding.maxApps2.setOnClickListener(this)
        binding.maxApps3.setOnClickListener(this)
        binding.maxApps4.setOnClickListener(this)
        binding.maxApps5.setOnClickListener(this)

        binding.textSize1.setOnClickListener(this)
        binding.textSize2.setOnClickListener(this)
        binding.textSize3.setOnClickListener(this)
        binding.textSize4.setOnClickListener(this)
        binding.textSize5.setOnClickListener(this)
        binding.textSize6.setOnClickListener(this)
        binding.textSize7.setOnClickListener(this)
        binding.textSize8.setOnClickListener(this)
        binding.textSize9.setOnClickListener(this)
        binding.textSize10.setOnClickListener(this)

        
        binding.alignment.setOnLongClickListener(this)
        binding.appThemeText.setOnLongClickListener(this)
        binding.swipeLeftApp.setOnLongClickListener(this)
        binding.swipeRightApp.setOnLongClickListener(this)
        binding.toggleLock.setOnLongClickListener(this)
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            viewModel.showDialog.postValue(Constants.Dialog.ABOUT)
            prefs.firstSettingsOpen = false
        }
        viewModel.isSlauncherDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
                prefs.toShowHintCounter += 1
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }
        viewModel.updateSwipeApps.observe(viewLifecycleOwner) {
            populateSwipeApps()
        }
    }

    private fun showMediaChooserDialog() {
        try {
            val mediaSessionManager = requireContext().getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComp = ComponentName(requireContext(), com.dev.launcher.media.MediaNotificationListener::class.java)
            val controllers: List<MediaController>? = try {
                mediaSessionManager.getActiveSessions(listenerComp)
            } catch (e: Exception) {
                null
            }

            val labels = ArrayList<String>()
            val pkgs = ArrayList<String>()
            val pm = requireContext().packageManager

            if (!controllers.isNullOrEmpty()) {
                for (c in controllers) {
                    val pkg = c.packageName
                    if (!pkgs.contains(pkg)) {
                        var label = pkg
                        try {
                            val ai = pm.getApplicationInfo(pkg, 0)
                            val appLabel = pm.getApplicationLabel(ai)
                            label = appLabel.toString()
                        } catch (ignored: Exception) { }
                        labels.add(label)
                        pkgs.add(pkg)
                    }
                }
            }

            // Also query installed launcher apps so user can select any media app even if not currently playing
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchableApps = pm.queryIntentActivities(mainIntent, 0)
            for (ri in launchableApps) {
                val pkg = ri.activityInfo.packageName
                if (!pkgs.contains(pkg)) {
                    val category = ri.activityInfo.applicationInfo.category
                    val isMedia = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && category == ApplicationInfo.CATEGORY_AUDIO) ||
                            pkg.contains("music", ignoreCase = true) ||
                            pkg.contains("media", ignoreCase = true) ||
                            pkg.contains("audio", ignoreCase = true) ||
                            pkg.contains("spotify", ignoreCase = true) ||
                            pkg.contains("podcast", ignoreCase = true) ||
                            pkg.contains("radio", ignoreCase = true) ||
                            pkg.contains("player", ignoreCase = true) ||
                            pkg.contains("youtube", ignoreCase = true) ||
                            pkg.contains("vlc", ignoreCase = true) ||
                            pkg.contains("sound", ignoreCase = true)

                    if (isMedia) {
                        val label = ri.loadLabel(pm).toString()
                        labels.add(label)
                        pkgs.add(pkg)
                    }
                }
            }

            // If no specific media apps matched filter, add all launchable apps so user has total freedom
            if (pkgs.isEmpty()) {
                for (ri in launchableApps) {
                    val pkg = ri.activityInfo.packageName
                    if (!pkgs.contains(pkg)) {
                        val label = ri.loadLabel(pm).toString()
                        labels.add(label)
                        pkgs.add(pkg)
                    }
                }
            }

            val items = labels.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.select_media_app)
                .setItems(items) { _: DialogInterface, which: Int ->
                    val pkg = pkgs[which]
                    val prefs = requireContext().getSharedPreferences("home_media", Context.MODE_PRIVATE)
                    prefs.edit().putString("selected_pkg", pkg).apply()
                    binding.mediaAppName?.text = labels[which]
                }
                .show()
        } catch (e: SecurityException) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.permission_required)
                .setMessage(R.string.enable_notification_listener)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun toggleSwipeLeft() {
        prefs.swipeLeftEnabled = !prefs.swipeLeftEnabled
        if (prefs.swipeLeftEnabled) {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_left_app_enabled))
        } else {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_left_app_disabled))
        }
    }

    private fun toggleSwipeRight() {
        prefs.swipeRightEnabled = !prefs.swipeRightEnabled
        if (prefs.swipeRightEnabled) {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_right_app_enabled))
        } else {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_right_app_disabled))
        }
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
    }

    private fun populateStatusBar() {
        if (prefs.showStatusBar) {
            showStatusBar()
            binding.statusBar.text = getString(R.string.on)
        } else {
            hideStatusBar()
            binding.statusBar.text = getString(R.string.off)
        }
    }

    private fun toggleDateTime(selected: Int) {
        prefs.dateTimeVisibility = selected
        populateDateTime()
        viewModel.toggleDateTime()
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            when (prefs.dateTimeVisibility) {
                Constants.DateTime.DATE_ONLY -> R.string.date
                Constants.DateTime.ON -> R.string.on
                else -> R.string.off
            }
        )
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

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS)
        )
    }

    private fun checkAdminPermission() {
        val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            prefs.lockModeOn = isAdmin
    }

    private fun toggleAccessibilityVisibility(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            binding.notWorking.visibility = View.VISIBLE
        if (isAccessServiceEnabled(requireContext()))
            binding.actionAccessibility.text = getString(R.string.disable)
        binding.accessibilityLayout.isVisible = show
        binding.scrollView.animateAlpha(if (show) 0.5f else 1f)
    }

    private fun openAccessibilityService() {
        toggleAccessibilityVisibility(false)
        
        populateLockSettings()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleLockMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            toggleAccessibilityVisibility(true)
            if (prefs.lockModeOn) {
                prefs.lockModeOn = false
                removeActiveAdmin()
            }
        } else {
            val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
            if (isAdmin) {
                removeActiveAdmin("Admin permission removed.")
                prefs.lockModeOn = false
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_permission_message)
                )
                requireActivity().startActivityForResult(intent, Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        populateLockSettings()
    }

    private fun removeActiveAdmin(toastMessage: String? = null) {
        try {
            deviceManager.removeActiveAdmin(componentName) 
            requireContext().showToast(toastMessage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun updateHomeAppsNum(num: Int) {
        binding.homeAppsNum.text = num.toString()
        binding.appsNumSelectLayout.visibility = View.GONE
        prefs.homeAppsNum = num
        viewModel.refreshHome(true)
    }

    private fun updateTextSizeScale(sizeScale: Float) {
        if (prefs.textSizeScale == sizeScale) return
        prefs.textSizeScale = sizeScale
        requireActivity().recreate()
    }

    private fun toggleKeyboardText() {
        if (prefs.autoShowKeyboard && prefs.keyboardMessageShown.not()) {
            viewModel.showDialog.postValue(Constants.Dialog.KEYBOARD)
            prefs.keyboardMessageShown = true
        } else {
            prefs.autoShowKeyboard = !prefs.autoShowKeyboard
            populateKeyboardText()
        }
    }

    private fun updateTheme(appTheme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == appTheme) return
        prefs.appTheme = appTheme
        populateAppThemeText(appTheme)
        setAppTheme(appTheme)
    }

    private fun setAppTheme(theme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == theme) return
        setPlainWallpaper(theme)
        requireActivity().recreate()
    }

    private fun setPlainWallpaper(appTheme: Int) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> setPlainWallpaper(requireContext(), android.R.color.black)
            AppCompatDelegate.MODE_NIGHT_NO -> setPlainWallpaper(requireContext(), android.R.color.white)
            else -> {
                if (requireContext().isDarkThemeOn())
                    setPlainWallpaper(requireContext(), android.R.color.black)
                else setPlainWallpaper(requireContext(), android.R.color.white)
            }
        }
    }

    private fun populateAppThemeText(appTheme: Int = prefs.appTheme) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> binding.appThemeText.text = getString(R.string.dark)
            AppCompatDelegate.MODE_NIGHT_NO -> binding.appThemeText.text = getString(R.string.light)
            else -> binding.appThemeText.text = getString(R.string.system_default)
        }
    }

    private fun populateFontText() {
        binding.fontText?.text = when (prefs.fontSelection) {
            Constants.Font.INTER -> getString(R.string.inter)
            Constants.Font.UBUNTU -> getString(R.string.ubuntu)
            Constants.Font.EBGARAMOND -> getString(R.string.eb_garamond)
            Constants.Font.CRIMSONPRO -> getString(R.string.crimson_pro)
            Constants.Font.CUSTOM -> prefs.customFontName?.ifEmpty { getString(R.string.custom_font) } ?: getString(R.string.custom_font)
            else -> getString(R.string.default_font)
        }
        val hasCustom = FontManager.hasCustomFontFile(requireContext())
        binding.importCustomFontStatus?.text = if (hasCustom) {
            prefs.customFontName?.ifEmpty { "custom_font.ttf" } ?: "custom_font.ttf"
        } else {
            getString(R.string.choose_font)
        }
    }

    private fun applyCustomFont(uri: Uri) {
        val success = FontManager.saveCustomFont(requireContext(), uri)
        if (success) {
            populateFontText()
            requireContext().showToast(R.string.font_applied)
            requireActivity().recreate()
        } else {
            requireContext().showToast(R.string.invalid_font_file)
        }
    }

    private fun populateAutoOpenApp() {
        binding.autoOpenApp?.text = if (prefs.autoOpenApp) getString(R.string.on) else getString(R.string.off)
    }

    private fun toggleAutoOpenApp() {
        prefs.autoOpenApp = !prefs.autoOpenApp
        populateAutoOpenApp()
    }

    private fun updateFont(fontId: Int) {
        if (fontId == Constants.Font.CUSTOM && !FontManager.hasCustomFontFile(requireContext())) {
            pickFontLauncher.launch(arrayOf("*/*"))
            binding.fontSelectLayout?.visibility = View.GONE
            return
        }
        if (prefs.fontSelection == fontId) {
            binding.fontSelectLayout?.visibility = View.GONE
            return
        }
        prefs.fontSelection = fontId
        FontManager.clear()
        populateFontText()
        binding.fontSelectLayout?.visibility = View.GONE
        requireActivity().recreate()
    }

    private fun populateTextSize() {
        binding.textSizeValue.text = when (prefs.textSizeScale) {
            Constants.TextSize.ONE -> 1
            Constants.TextSize.TWO -> 2
            Constants.TextSize.THREE -> 3
            Constants.TextSize.FOUR -> 4
            Constants.TextSize.FIVE -> 5
            Constants.TextSize.SIX -> 6
            Constants.TextSize.SEVEN -> 7
            Constants.TextSize.EIGHT -> 8
            Constants.TextSize.NINE -> 9
            Constants.TextSize.TEN -> 10
            else -> "--"
        }.toString()
    }

    private fun populateScreenTimeOnOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requireContext().appUsagePermissionGranted()) binding.screenTimeOnOff.text = getString(R.string.on)
            else binding.screenTimeOnOff.text = getString(R.string.off)
        } else binding.screenTimeLayout.visibility = View.GONE
    }

    private fun populateKeyboardText() {
        if (prefs.autoShowKeyboard) binding.autoShowKeyboard.text = getString(R.string.on)
        else binding.autoShowKeyboard.text = getString(R.string.off)
    }

    

    private fun updateHomeBottomAlignment() {
        if (viewModel.isSlauncherDefault.value != true) {
            requireContext().showToast(getString(R.string.please_set_slauncher_as_default_first), Toast.LENGTH_LONG)
            return
        }
        prefs.homeBottomAlignment = !prefs.homeBottomAlignment
        populateAlignment()
        viewModel.updateHomeAlignment(prefs.homeAlignment)
    }

    private fun populateAlignment() {
        when (prefs.homeAlignment) {
            Gravity.START -> binding.alignment.text = getString(R.string.left)
            Gravity.CENTER -> binding.alignment.text = getString(R.string.center)
            Gravity.END -> binding.alignment.text = getString(R.string.right)
        }
        binding.alignmentBottom.text = if (prefs.homeBottomAlignment)
            getString(R.string.bottom_on)
        else getString(R.string.bottom_off)
    }

    private fun populateLockSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.toggleLock.text = getString(
                if (isAccessServiceEnabled(requireContext())) R.string.on
                else R.string.off
            )
        } else {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn) R.string.on
                else R.string.off
            )
        }
    }

    private fun populateSwipeDownAction() {
        binding.swipeDownAction.text = when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.NOTIFICATIONS -> getString(R.string.notifications)
            else -> getString(R.string.search)
        }
    }

    private fun updateSwipeDownAction(swipeDownFor: Int) {
        if (prefs.swipeDownAction == swipeDownFor) return
        prefs.swipeDownAction = swipeDownFor
        populateSwipeDownAction()
    }

    private fun populateSwipeApps() {
        binding.swipeLeftApp.text = prefs.appNameSwipeLeft
        binding.swipeRightApp.text = prefs.appNameSwipeRight
        if (!prefs.swipeLeftEnabled)
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
        if (!prefs.swipeRightEnabled)
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
    }







    private fun showAppListIfEnabled(flag: Int) {
        if ((flag == Constants.FLAG_SET_SWIPE_LEFT_APP) and !prefs.swipeLeftEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        if ((flag == Constants.FLAG_SET_SWIPE_RIGHT_APP) and !prefs.swipeRightEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        viewModel.getAppList(true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to flag)
        )
    }

    private fun populateProMessage() {
        if (prefs.proMessageShown.not() && prefs.userState == Constants.UserState.SHARE) {
            prefs.proMessageShown = true
            viewModel.showDialog.postValue(Constants.Dialog.PRO_MESSAGE)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        viewModel.checkForMessages.call()
        super.onDestroy()
    }
}