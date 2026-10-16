package com.example.safeqr.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.safeqr.MainActivity
import com.example.safeqr.R

/**
 * 빠른 설정 패널에서 탭하면 바로 스캔 화면(MainActivity)을 연다.
 * 퀵 설정 타일은 API 24 (Android 7.0) 이상에서만 동작한다.
 */
class ScanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE // 토글형이 아닌 '실행 버튼'형 타일
            label = getString(R.string.tile_label)
            icon = Icon.createWithResource(this@ScanTileService, R.drawable.ic_tile_qr)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // 잠금 화면에서 바로 카메라 앱 화면을 띄우지 않도록, 잠겨 있으면 먼저 잠금 해제를 요구
        if (isLocked) {
            unlockAndRun { launchScanner() }
        } else {
            launchScanner()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchScanner() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 에서는 Intent 버전이 막혀 있어 PendingIntent 버전을 써야 함
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}