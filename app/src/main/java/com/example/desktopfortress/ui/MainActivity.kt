package com.example.desktopfortress.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.example.desktopfortress.R
import com.example.desktopfortress.databinding.ActivityMainBinding
import com.example.desktopfortress.manager.SpatialManager
import com.example.desktopfortress.domain.model.PlaneScanStatus
import com.example.desktopfortress.ui.game.GameViewModel
import com.example.desktopfortress.manager.GameManager
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

/** SpatialLaunchActivity extends ComponentActivity through the PICO stub hierarchy. */
class MainActivity : SpatialLaunchActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: GameViewModel

    private val statusCallback = SpatialManager.ScanStatusCallback { status ->
        runOnUiThread {
            binding.accessibilityStatus.text = when (status) {
                PlaneScanStatus.Idle -> getString(R.string.spatial_initializing)
                PlaneScanStatus.Scanning -> "正在扫描水平地面"
                is PlaneScanStatus.Success -> "地面识别成功"
                is PlaneScanStatus.Failed -> "地面识别失败，已启用地面高度兜底棋盘"
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = REQUIRED_PERMISSIONS.all { result[it] == true || hasPermission(it) }
        viewModel.onPermissionsResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        // SpatialStubActivity owns the visible spatial root. The binding remains
        // lifecycle-scoped state/accessibility plumbing and is intentionally not
        // attached as a second Android Surface over the SpatialUI window.
        viewModel = ViewModelProvider(this)[GameViewModel::class.java]
        SpatialManager.initialize(application)
        SpatialManager.addScanStatusCallback(statusCallback)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()
        requestSpatialPermissions()
    }

    override fun onResume() {
        super.onResume()
        GameManager.onAppForegrounded()
        enableImmersiveMode()
    }

    override fun onPause() {
        GameManager.onAppBackgrounded()
        super.onPause()
    }

    override fun onDestroy() {
        SpatialManager.removeScanStatusCallback(statusCallback)
        if (isFinishing) SpatialManager.destroy()
        super.onDestroy()
    }

    private fun requestSpatialPermissions() {
        val missing = REQUIRED_PERMISSIONS.filterNot(::hasPermission)
        if (missing.isEmpty()) {
            viewModel.onPermissionsResult(true)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
