package com.example.safeqr.scan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * 웹 주소가 아닌 QR 을 사용자가 원할 때 '시스템 화면까지만' 열어 주는 동작.
 *
 * 앱이 직접 전화를 걸거나 문자를 보내거나 Wi-Fi 에 연결하지 않는다.
 *  - 전화: 번호가 입력된 전화 앱 화면 (ACTION_DIAL, 권한 불필요) → 통화 버튼은 사용자가 누름
 *  - 문자: 번호·내용이 채워진 문자 작성 화면 (ACTION_SENDTO) → 보내기는 사용자가 누름
 *  - 메일: 받는 주소가 채워진 메일 작성 화면 (ACTION_SENDTO) → 보내기는 사용자가 누름
 *  - Wi-Fi: 시스템의 "네트워크 저장" 창 (Android 11+) → 저장은 사용자가 누름
 */
data class QrAction(
    val type: String,          // "dial" / "sms" / "email" / "wifi"
    val target: String,        // 전화번호, 메일 주소, Wi-Fi 이름
    val extra: String? = null, // 문자 내용, Wi-Fi 비밀번호
    val wifiType: Int = 0,     // Barcode.WiFi.TYPE_OPEN / TYPE_WPA / TYPE_WEP
) {
    /** 버튼에 표시할 문구 */
    val label: String
        get() = when (type) {
            "dial" -> "전화 앱으로 열기"
            "sms" -> "문자 앱으로 열기"
            "email" -> "메일 앱으로 열기"
            else -> "Wi-Fi 연결하기"
        }

    fun toBundle(): Bundle = Bundle().apply {
        putString(K_TYPE, type)
        putString(K_TARGET, target)
        putString(K_EXTRA, extra)
        putInt(K_WIFI, wifiType)
    }

    /** 실제 실행. 실패해도 앱이 죽지 않도록 안내만 띄운다. */
    fun launch(activity: Activity) {
        try {
            when (type) {
                "dial" -> activity.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", target, null))
                )
                "sms" -> activity.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", target, null)).apply {
                        if (!extra.isNullOrBlank()) putExtra("sms_body", extra)
                    }
                )
                "email" -> activity.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", target, null))
                )
                "wifi" -> openWifi(activity)
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "이 동작을 처리할 앱이 없어요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWifi(activity: Activity) {
        // Android 11 이상: 시스템 "네트워크 저장" 창 (WEP 는 지원하지 않음)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifiType != Barcode.WiFi.TYPE_WEP) {
            try {
                val builder = WifiNetworkSuggestion.Builder().setSsid(target)
                if (wifiType == Barcode.WiFi.TYPE_WPA && !extra.isNullOrEmpty()) {
                    builder.setWpa2Passphrase(extra)
                }
                val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                    putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(builder.build()))
                }
                activity.startActivity(intent)
                return
            } catch (e: Exception) {
                // 비밀번호 길이가 규격(8~63자)에 맞지 않는 등 → 아래 대체 방법으로
            }
        }

        // 대체 방법: 비밀번호를 복사해 두고 Wi-Fi 설정 화면을 연다
        if (!extra.isNullOrEmpty()) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Wi-Fi 비밀번호", extra)
            // Android 13+ : 복사 미리보기에 비밀번호가 보이지 않도록 '민감한 정보'로 표시
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, "비밀번호를 복사했어요. '$target'을 선택해 붙여 넣으세요.", Toast.LENGTH_LONG).show()
        }
        activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    companion object {
        private const val K_TYPE = "type"
        private const val K_TARGET = "target"
        private const val K_EXTRA = "extra"
        private const val K_WIFI = "wifi"

        fun fromBundle(b: Bundle): QrAction? {
            val type = b.getString(K_TYPE) ?: return null
            val target = b.getString(K_TARGET) ?: return null
            return QrAction(type, target, b.getString(K_EXTRA), b.getInt(K_WIFI))
        }
    }
}