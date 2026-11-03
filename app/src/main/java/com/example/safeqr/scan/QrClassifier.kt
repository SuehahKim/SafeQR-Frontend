package com.example.safeqr.scan

import android.util.Patterns
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * 웹 주소가 아닌 QR(Wi-Fi, 전화, 문자 등)을 서버 없이 앱에서 판별한 결과.
 * 결과 시트(ResultBottomSheet)에 그대로 넘겨서 보여 준다.
 */
data class LocalResult(
    val kind: String,          // 시트에 표시할 QR 종류 (예: "Wi-Fi QR")
    val level: String,         // "info" = 확인 필요, "warning" = 주의
    val description: String,  // 헤더 아래 한 줄 설명
    val content: String,       // 사람이 읽을 수 있게 정리한 QR 내용
    val reasons: List<String>, // 판단 근거
)

/**
 * QR 종류 판별기.
 *
 * 원칙 (허용 목록 방식):
 *  - http/https 웹 주소만 서버로 보내 분석하고, 접속 버튼을 허용한다.
 *  - Wi-Fi·전화·문자·이메일은 내용을 먼저 보여 주고, 객관적인 위험 신호가 있을 때만 '주의'.
 *  - 그 밖의 형식(앱 실행 링크, javascript:, file: 등)은 열지 않고 '주의'.
 *  - 어떤 경우에도 앱이 자동으로 연결·발신·실행하지 않는다.
 */
object QrClassifier {

    // "intent://", "javascript:alert", "WIFI:S:" 처럼 스킴 바로 뒤에 공백이 없는 형태만 링크로 본다.
    // ("Note: 내일 3시" 같은 일반 문장은 콜론 뒤에 공백이 있어서 텍스트로 처리)
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:\\S")

    /**
     * 서버로 보낼 웹 주소로 바꿀 수 있으면 그 주소를, 아니면 null 을 돌려준다.
     *  - "https://..." / "HTTP://..." → 그대로
     *  - "www.naver.com", "naver.com/abc" 처럼 앞부분(스킴)이 빠진 주소 → "https://" 를 붙임
     */
    fun toWebUrl(raw: String): String? {
        val value = raw.trim()
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) return value

        // 공백이 있거나, http 이외의 스킴(ftp://, tel:, WIFI: 등)이 붙어 있으면 웹 주소가 아님
        if (value.any { it.isWhitespace() } || value.contains("://") || SCHEME.containsMatchIn(value)) return null

        // 안드로이드 기본 제공 웹 주소 패턴으로 "도메인처럼 생겼는지" 확인
        return if (Patterns.WEB_URL.matcher(value).matches()) "https://$value" else null
    }

    /** 웹 주소가 아닌 QR 을 종류별로 판별한다. (toWebUrl 이 null 일 때만 호출) */
    fun classify(barcode: Barcode, raw: String): LocalResult = when (barcode.valueType) {
        Barcode.TYPE_WIFI -> wifi(barcode, raw)
        Barcode.TYPE_PHONE -> phone(barcode.phone?.number ?: raw.substringAfter(':'))
        Barcode.TYPE_SMS -> sms(barcode.sms?.phoneNumber, barcode.sms?.message, raw)
        Barcode.TYPE_EMAIL -> email(barcode.email?.address ?: raw)
        else -> other(raw)
    }

    // ── Wi-Fi ────────────────────────────────────────────────────────────────
    private fun wifi(barcode: Barcode, raw: String): LocalResult {
        val ssid = barcode.wifi?.ssid?.takeIf { it.isNotBlank() } ?: "(이름 없음)"
        val type = barcode.wifi?.encryptionType
        val security = when (type) {
            Barcode.WiFi.TYPE_OPEN -> "없음 (개방형)"
            Barcode.WiFi.TYPE_WEP -> "WEP (오래된 방식)"
            Barcode.WiFi.TYPE_WPA -> "WPA/WPA2"
            else -> "알 수 없음"
        }

        val reasons = mutableListOf<String>()
        when (type) {
            Barcode.WiFi.TYPE_OPEN ->
                reasons += "비밀번호가 없는 개방형 네트워크예요. 같은 Wi-Fi에 있는 다른 사람이 통신을 엿볼 수 있어요."
            Barcode.WiFi.TYPE_WEP ->
                reasons += "WEP는 오래된 보안 방식이라 쉽게 뚫릴 수 있어요."
        }
        val risky = reasons.isNotEmpty()
        reasons += "자동으로 연결하지 않았어요. 매장 안내문 등의 네트워크 이름과 같은지 확인하세요."

        return LocalResult(
            kind = "Wi-Fi QR",
            level = if (risky) "warning" else "info",
            description = if (risky) "보안이 약한 Wi-Fi예요. 연결에 주의하세요." else "Wi-Fi 연결 정보가 담긴 QR이에요.",
            // 비밀번호는 화면에 보여 주지 않음 (옆 사람이 볼 수 있음)
            content = "네트워크 이름: $ssid\n보안 방식: $security",
            reasons = reasons,
        )
    }

    // ── 전화 ────────────────────────────────────────────────────────────────
    private fun phone(number: String): LocalResult {
        val reasons = numberWarnings(number).toMutableList()
        val risky = reasons.isNotEmpty()
        reasons += "자동으로 전화를 걸지 않았어요. 공식 번호인지 확인하세요."
        return LocalResult(
            kind = "전화 QR",
            level = if (risky) "warning" else "info",
            description = if (risky) "주의가 필요한 번호예요." else "전화번호가 담긴 QR이에요.",
            content = "전화번호: $number",
            reasons = reasons,
        )
    }

    // ── 문자 ────────────────────────────────────────────────────────────────
    private fun sms(number: String?, message: String?, raw: String): LocalResult {
        val to = number?.takeIf { it.isNotBlank() } ?: raw
        val reasons = numberWarnings(to).toMutableList()
        val risky = reasons.isNotEmpty()
        if (!message.isNullOrBlank()) reasons += "미리 작성된 문자 내용이 있어요. 보내기 전에 내용을 꼭 확인하세요."
        reasons += "자동으로 문자를 보내지 않았어요."
        return LocalResult(
            kind = "문자 QR",
            level = if (risky) "warning" else "info",
            description = if (risky) "주의가 필요한 번호예요." else "문자 보내기 정보가 담긴 QR이에요.",
            content = buildString {
                append("받는 번호: $to")
                if (!message.isNullOrBlank()) append("\n내용: $message")
            },
            reasons = reasons,
        )
    }

    /** 060 유료 번호, 해외 번호 확인 (+82 는 국내 번호로 취급) */
    private fun numberWarnings(number: String): List<String> {
        val digits = number.filter { it.isDigit() || it == '+' }
        val domestic = if (digits.startsWith("+82")) "0" + digits.drop(3) else digits
        val warnings = mutableListOf<String>()
        if (domestic.startsWith("060")) {
            warnings += "060으로 시작하는 유료 정보 서비스 번호예요. 요금이 많이 나올 수 있어요."
        }
        if (domestic.startsWith("+") || domestic.startsWith("00")) {
            warnings += "해외 번호예요. 국제 요금이 나올 수 있고, 사기에 자주 쓰여요."
        }
        return warnings
    }

    // ── 이메일 ──────────────────────────────────────────────────────────────
    private fun email(address: String) = LocalResult(
        kind = "이메일 QR",
        level = "info",
        description = "이메일 주소가 담긴 QR이에요.",
        content = "받는 주소: $address",
        reasons = listOf("자동으로 메일을 보내지 않았어요. 받는 주소가 맞는지 확인하세요."),
    )

    // ── 그 외 ──────────────────────────────────────────────────────────────
    private fun other(raw: String): LocalResult =
        if (SCHEME.containsMatchIn(raw)) {
            // intent://, market://, javascript:, file:, ftp:// 등 → 허용 목록에 없으므로 열지 않음
            LocalResult(
                kind = "알 수 없는 형식",
                level = "warning",
                description = "열 수 없는 형식의 QR이에요.",
                content = raw,
                reasons = listOf(
                    "웹 주소가 아닌 링크예요. 앱을 실행하거나 코드를 실행하는 링크일 수 있어 열지 않았어요.",
                ),
            )
        } else {
            LocalResult(
                kind = "텍스트 QR",
                level = "info",
                description = "링크가 아닌 일반 텍스트예요.",
                content = raw,
                reasons = listOf("텍스트 안에 주소가 적혀 있다면 바로 접속하지 말고 출처를 확인하세요."),
            )
        }
}