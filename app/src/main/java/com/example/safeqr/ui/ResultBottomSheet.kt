package com.example.safeqr.ui

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.TextViewCompat
import com.example.safeqr.R
import com.example.safeqr.browser.SafeBrowser
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 스캔 결과를 보여주는 하단 시트.
 *
 * - 웹 주소: 서버 분석 결과(level / score / reasons)를 표시하고, 접속 버튼 제공
 * - Wi-Fi·전화·문자 등: 앱에서 판별한 내용을 표시 (점수 없음, 접속 버튼 없음)
 * - level 에 따라 헤더 카드 배경색, 아이콘, 제목이 바뀐다.
 * - 시트가 닫히면 FragmentResult 로 Activity 에 알린다 → Activity 가 스캔을 재개한다.
 */
class ResultBottomSheet : BottomSheetDialogFragment() {

    private enum class Level(
        @ColorRes val accentRes: Int,
        @ColorRes val containerRes: Int,
        @DrawableRes val iconRes: Int,
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int,
    ) {
        SAFE(
            R.color.safeqr_safe, R.color.safeqr_safe_container, R.drawable.ic_level_safe,
            R.string.result_title_safe, R.string.result_desc_safe,
        ),
        INFO(
            R.color.safeqr_info, R.color.safeqr_info_container, R.drawable.ic_level_info,
            R.string.result_title_info, R.string.result_desc_info,
        ),
        WARNING(
            R.color.safeqr_warning, R.color.safeqr_warning_container, R.drawable.ic_level_warning,
            R.string.result_title_warning, R.string.result_desc_warning,
        ),
        DANGER(
            R.color.safeqr_danger, R.color.safeqr_danger_container, R.drawable.ic_level_danger,
            R.string.result_title_danger, R.string.result_desc_danger,
        );

        companion object {
            /** 알 수 없는 값(null, 오타, 서버 스펙 변경)은 WARNING 으로 처리 → 실패해도 안전한 쪽(fail-safe) */
            fun from(raw: String?): Level = when (raw?.trim()?.lowercase()) {
                "safe" -> SAFE
                "info" -> INFO
                "danger" -> DANGER
                else -> WARNING
            }
        }
    }

    private val url: String by lazy { requireArguments().getString(ARG_URL).orEmpty() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_result, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val level = Level.from(args.getString(ARG_LEVEL))
        val score = args.getInt(ARG_SCORE, NO_SCORE)
        val reasons = args.getStringArrayList(ARG_REASONS).orEmpty()
        val kind = args.getString(ARG_KIND)                       // null 이면 웹 주소 결과
        val description = args.getString(ARG_DESC)
        val content = args.getString(ARG_CONTENT) ?: url
        val openable = args.getBoolean(ARG_OPENABLE, true)

        val ctx = requireContext()
        val accent = ContextCompat.getColor(ctx, level.accentRes)
        val containerColor = ContextCompat.getColor(ctx, level.containerRes)

        // 1) 헤더: 배경색 + 아이콘 + 제목/설명
        view.findViewById<MaterialCardView>(R.id.cardHeader).apply {
            setCardBackgroundColor(containerColor)
            strokeColor = accent
        }
        view.findViewById<ImageView>(R.id.ivLevelIcon).apply {
            setImageResource(level.iconRes)
            imageTintList = ColorStateList.valueOf(accent)
            contentDescription = getString(level.titleRes)
        }
        view.findViewById<TextView>(R.id.tvLevelTitle).apply {
            setText(level.titleRes)
            setTextColor(accent)
        }
        view.findViewById<TextView>(R.id.tvLevelDesc).apply {
            if (description != null) text = description else setText(level.descRes)
        }
        // 서버 점수가 없는 결과(Wi-Fi·전화 등)는 점수 칸을 숨김
        view.findViewById<TextView>(R.id.tvScore).apply {
            if (score == NO_SCORE) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = getString(R.string.result_score_format, score)
            }
        }

        // 2) 내용 (웹 주소면 '스캔한 주소', 그 외에는 'Wi-Fi QR 내용' 처럼 종류 표시)
        view.findViewById<TextView>(R.id.tvUrlLabel).apply {
            text = if (kind != null) getString(R.string.result_label_content, kind)
            else getString(R.string.result_label_url)
        }
        view.findViewById<TextView>(R.id.tvUrl).text = content

        // 3) 판단 근거 목록
        val reasonsContainer = view.findViewById<LinearLayout>(R.id.reasonsContainer)
        reasonsContainer.removeAllViews()
        val items = reasons.ifEmpty { listOf(getString(R.string.result_no_reasons)) }
        items.forEach { reason ->
            reasonsContainer.addView(TextView(ctx).apply {
                text = getString(R.string.result_reason_bullet, reason)
                TextViewCompat.setTextAppearance(
                    this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setPadding(0, dp(2), 0, dp(2))
            })
        }

        // 4) 버튼 (웹 주소일 때만 접속 가능)
        view.findViewById<MaterialButton>(R.id.btnOpen).apply {
            visibility = if (openable && level == Level.SAFE) View.VISIBLE else View.GONE
            backgroundTintList = ColorStateList.valueOf(accent)
            // 배경만 바꾸면 글자가 테마 기본색으로 남아 잘 안 보이므로 글자·아이콘 색도 함께 지정
            val onAccent = ContextCompat.getColor(ctx, R.color.safeqr_on_accent)
            setTextColor(onAccent)
            iconTint = ColorStateList.valueOf(onAccent)
            setOnClickListener {
                SafeBrowser.open(requireActivity(), url)
                dismiss()
            }
        }
        // 주의·위험: 기본은 막되, 사용자가 확인 팝업을 한 번 더 거치면 접속 가능
        view.findViewById<MaterialButton>(R.id.btnOpenAnyway).apply {
            val risky = level == Level.WARNING || level == Level.DANGER
            visibility = if (openable && risky) View.VISIBLE else View.GONE
            setTextColor(accent)
            setOnClickListener { confirmRiskyOpen(level) }
        }
        view.findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        // 처음부터 완전히 펼친 상태로 표시 (중간 단계에서 멈추지 않게)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // 회전 등으로 다시 만들어지는 경우에는 '닫힘'으로 보지 않는다.
        if (isAdded && activity?.isChangingConfigurations != true) {
            val key = requireArguments().getString(ARG_KEY) ?: url
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY, bundleOf(RESULT_URL to key)
            )
        }
    }

    /** 위험 링크 접속 전 마지막 확인. 기본 선택은 '취소' 쪽으로 둔다. */
    private fun confirmRiskyOpen(level: Level) {
        val message = if (level == Level.DANGER) R.string.confirm_msg_danger else R.string.confirm_msg_warning
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_title)
            .setMessage(getString(message) + "\n\n" + url)   // 실제로 열릴 주소를 한 번 더 보여 줌
            .setNegativeButton(R.string.confirm_cancel, null)
            .setPositiveButton(R.string.confirm_open) { _, _ ->
                SafeBrowser.open(requireActivity(), url)
                dismiss()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "ResultBottomSheet"
        const val REQUEST_KEY = "safeqr_result_dismissed"
        const val RESULT_URL = "url"
        const val NO_SCORE = -1

        private const val ARG_URL = "arg_url"
        private const val ARG_LEVEL = "arg_level"
        private const val ARG_SCORE = "arg_score"
        private const val ARG_REASONS = "arg_reasons"
        private const val ARG_KIND = "arg_kind"
        private const val ARG_DESC = "arg_desc"
        private const val ARG_CONTENT = "arg_content"
        private const val ARG_OPENABLE = "arg_openable"
        private const val ARG_KEY = "arg_key"

        /**
         * 웹 주소 결과 (서버 분석).
         * key: 같은 QR 을 다시 찍었는지 비교할 값 (QR 원문). 기본값은 url.
         */
        fun newInstance(
            url: String,
            level: String?,
            score: Int,
            reasons: List<String>?,
            key: String = url,
        ) = ResultBottomSheet().apply {
            arguments = bundleOf(
                ARG_URL to url,
                ARG_LEVEL to level,
                ARG_SCORE to score,
                ARG_REASONS to ArrayList(reasons.orEmpty()),
                ARG_KEY to key,
            )
        }

        /** 웹 주소가 아닌 QR 결과 (앱에서 판별, 점수·접속 버튼 없음) */
        fun forLocal(
            raw: String,
            kind: String,
            level: String,
            description: String,
            content: String,
            reasons: List<String>,
        ) = ResultBottomSheet().apply {
            arguments = bundleOf(
                ARG_URL to raw,
                ARG_LEVEL to level,
                ARG_SCORE to NO_SCORE,
                ARG_REASONS to ArrayList(reasons),
                ARG_KIND to kind,
                ARG_DESC to description,
                ARG_CONTENT to content,
                ARG_OPENABLE to false,
                ARG_KEY to raw,
            )
        }
    }
}