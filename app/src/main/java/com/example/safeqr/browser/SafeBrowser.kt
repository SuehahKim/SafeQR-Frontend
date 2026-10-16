package com.example.safeqr.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import com.example.safeqr.R

/**
 * 검사를 통과한 URL 을 Chrome Custom Tabs 로 연다.
 *
 * WebView 대신 Custom Tabs 를 쓰는 이유 (보안 앱 관점):
 *  - 브라우저 자체의 Safe Browsing, 주소창(실제 도메인 표시), 자동 업데이트 보안 패치를 그대로 받음
 *  - 앱 프로세스 밖에서 렌더링 → JS 인터페이스·파일 접근 같은 WebView 설정 실수로 인한 공격면이 없음
 *  - 페이지 로딩도 WebView 보다 빠름 (브라우저 프로세스 재사용)
 */
object SafeBrowser {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    fun open(activity: Activity, rawUrl: String) {
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull()

        // startsWith("http") 는 "httpx://", "http:javascript..." 같은 것도 통과시키므로
        // 실제로 여는 시점에 스킴과 호스트를 한 번 더 엄격하게 검사한다.
        if (uri == null || uri.scheme?.lowercase() !in ALLOWED_SCHEMES || uri.host.isNullOrBlank()) {
            Toast.makeText(activity, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        val toolbarColor = ContextCompat.getColor(activity, R.color.safeqr_safe)
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false) // 사용자가 실제 도메인을 계속 볼 수 있게 주소창 고정
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(toolbarColor)
                    .build()
            )
            .build()

        try {
            intent.launchUrl(activity, uri)
        } catch (e: ActivityNotFoundException) {
            // Custom Tabs 미지원 기기 → 일반 브라우저로 폴백
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                )
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.browser_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }
}