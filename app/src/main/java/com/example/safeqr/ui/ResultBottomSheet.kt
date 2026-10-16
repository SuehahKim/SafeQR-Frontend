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

/**
 * 서버 분석 결과(level / score / reasons)를 보여주는 하단 시트.
 *
 * - level 에 따라 헤더 카드 배경색, 아이콘, 제목, 버튼 색이 바뀐다.
 * - '접속하기' 버튼은 SAFE 일 때만 노출된다.
 * - 시트가 닫히면 FragmentResult 로 Activity 에 알린다 → Activity 가 스캔을 재개한다.
 *   (람다 콜백 대신 FragmentResult 를 쓰는 이유: 화면 회전 후에도 연결이 끊기지 않음)
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
        val score = args.getInt(ARG_SCORE)
        val reasons = args.getStringArrayList(ARG_REASONS).orEmpty()

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
        view.findViewById<TextView>(R.id.tvLevelDesc).setText(level.descRes)
        view.findViewById<TextView>(R.id.tvScore).text =
            getString(R.string.result_score_format, score)

        // 2) URL (사용자가 실제 접속 대상을 눈으로 확인할 수 있도록 전체 표시)
        view.findViewById<TextView>(R.id.tvUrl).text = url

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

        // 4) 버튼
        view.findViewById<MaterialButton>(R.id.btnOpen).apply {
            visibility = if (level == Level.SAFE) View.VISIBLE else View.GONE
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
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY, bundleOf(RESULT_URL to url)
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "ResultBottomSheet"
        const val REQUEST_KEY = "safeqr_result_dismissed"
        const val RESULT_URL = "url"

        private const val ARG_URL = "arg_url"
        private const val ARG_LEVEL = "arg_level"
        private const val ARG_SCORE = "arg_score"
        private const val ARG_REASONS = "arg_reasons"

        /**
         * AnalyzeResponse 를 직접 받지 않고 원시 값으로 받는다.
         * → Parcelable 구현 없이 Bundle 에 담을 수 있고, 회전 후에도 복원된다.
         * level / reasons 를 nullable 로 받는 이유: Gson 은 Kotlin 의 non-null 선언을 무시하고
         *   서버가 필드를 빠뜨리면 null 을 넣는다. 여기서 받아내야 NPE 없이 WARNING 으로 처리된다.
         */
        fun newInstance(
            url: String,
            level: String?,
            score: Int,
            reasons: List<String>?,
        ) = ResultBottomSheet().apply {
            arguments = bundleOf(
                ARG_URL to url,
                ARG_LEVEL to level,
                ARG_SCORE to score,
                ARG_REASONS to ArrayList(reasons.orEmpty()),
            )
        }
    }
}