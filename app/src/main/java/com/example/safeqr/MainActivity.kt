package com.example.safeqr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.safeqr.scan.QrClassifier
import com.example.safeqr.ui.ResultBottomSheet
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var loadingOverlay: View
    private lateinit var cameraExecutor: ExecutorService

    // 스캐너는 프레임마다 새로 만들지 않고 한 번만 생성해서 재사용 (onDestroy 에서 close)
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    // 한 번 스캔되면 멈추게 하는 스위치.
    // 분석기 스레드(cameraExecutor)에서 읽고 메인 스레드에서 쓰므로 @Volatile 로 가시성 보장.
    @Volatile
    private var isScanning = true

    // 시트를 닫은 직후, 카메라 앞에 그대로 있는 같은 QR 이 곧바로 다시 인식되는 것을 막는 쿨다운
    // (QR 원문 기준으로 비교)
    private var lastHandledUrl: String? = null
    private var lastHandledAt = 0L

    // 앱이 백그라운드에 있을 때 결과가 나오면 여기에 보관했다가 돌아왔을 때 표시
    private var pendingSheet: ResultBottomSheet? = null

    // 카메라 권한 팝업 처리
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "QR 스캔을 위해 카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // activity_main.xml에 만들어둔 카메라 뷰파인더 연결
        viewFinder = findViewById(R.id.viewFinder)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 화면 회전으로 Activity 가 다시 만들어졌는데 결과 시트가 떠 있다면 스캔은 계속 멈춰 둔다
        if (supportFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) != null) {
            isScanning = false
        }

        // 결과 시트가 닫히면(닫기 / 접속하기 / 바깥 탭 / 뒤로가기) 여기로 들어옴 → 스캔 재개
        supportFragmentManager.setFragmentResultListener(
            ResultBottomSheet.REQUEST_KEY, this
        ) { _, bundle ->
            lastHandledUrl = bundle.getString(ResultBottomSheet.RESULT_URL)
            lastHandledAt = SystemClock.elapsedRealtime()
            isScanning = true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // 브라우저 등에서 돌아왔을 때도 같은 QR 이 바로 다시 잡히지 않도록 쿨다운을 새로 시작
        if (lastHandledUrl != null) lastHandledAt = SystemClock.elapsedRealtime()
    }

    override fun onPostResume() {
        super.onPostResume()
        // 백그라운드에 있는 동안 나온 결과가 있으면 이제 표시
        pendingSheet?.let {
            pendingSheet = null
            showSheet(it)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 1. 카메라 화면(Preview) 설정
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            // 2. 구글 ML Kit 이미지 분석기(QR 스캐너) 설정
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            // 후면 카메라 기본 선택
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Toast.makeText(this, "카메라 실행 실패", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // 카메라 프레임마다 QR코드가 있는지 검사하는 함수
    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && isScanning) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    // 프레임 처리 중에 다른 경로로 이미 멈췄을 수 있으므로 한 번 더 확인
                    if (!isScanning) return@addOnSuccessListener

                    // 한 화면에 QR 이 여러 개면 웹 주소를 우선, 없으면 내용이 있는 첫 번째 QR 하나만 처리
                    val barcode = barcodes.firstOrNull { b ->
                        b.rawValue?.let { QrClassifier.toWebUrl(it) } != null
                    } ?: barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                    ?: return@addOnSuccessListener

                    val raw = barcode.rawValue!!.trim()

                    val now = SystemClock.elapsedRealtime()
                    if (raw == lastHandledUrl && now - lastHandledAt < SAME_URL_COOLDOWN_MS) {
                        return@addOnSuccessListener
                    }

                    isScanning = false // 중복 스캔 방지

                    // 허용 목록 방식: 웹 주소만 서버로 보내고, 나머지는 앱에서 판별해 내용만 보여 줌
                    val webUrl = QrClassifier.toWebUrl(raw)
                    if (webUrl != null) {
                        requestAnalyze(webUrl, raw)
                    } else {
                        val r = QrClassifier.classify(barcode, raw)
                        showSheet(
                            ResultBottomSheet.forLocal(
                                raw = raw,
                                kind = r.kind,
                                level = r.level,
                                description = r.description,
                                content = r.content,
                                reasons = r.reasons,
                            )
                        )
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close() // 다음 프레임 분석을 위해 반드시 닫아주기
                }
        } else {
            imageProxy.close()
        }
    }

    /** url: 서버로 보낼 주소, raw: QR 원문 (쿨다운 비교용) */
    private fun requestAnalyze(url: String, raw: String) {
        loadingOverlay.visibility = View.VISIBLE

        // 1. 스캔한 URL을 데이터 상자에 담기
        val requestData = AnalyzeRequest(url)

        // 2. 백엔드 서버로 택배 보내기 (비동기 통신)
        NetworkManager.api.analyzeUrl(requestData).enqueue(object : retrofit2.Callback<AnalyzeResponse> {
            override fun onResponse(
                call: retrofit2.Call<AnalyzeResponse>,
                response: retrofit2.Response<AnalyzeResponse>
            ) {
                loadingOverlay.visibility = View.GONE
                val result = response.body()

                if (response.isSuccessful && result != null) {
                    // 3. 성공: 시트를 띄우고, isScanning 은 시트가 닫힐 때 복구한다.
                    showSheet(
                        ResultBottomSheet.newInstance(
                            url = url,
                            level = result.level,
                            score = result.score,
                            reasons = result.reasons,
                            key = raw,
                        )
                    )
                } else {
                    Toast.makeText(this@MainActivity, "서버 응답 오류 (코드: ${response.code()})", Toast.LENGTH_SHORT).show()
                    isScanning = true // 실패했으니 다시 스캔할 수 있게 열어줌 (본문이 비어 있는 경우 포함)
                }
            }

            override fun onFailure(call: retrofit2.Call<AnalyzeResponse>, t: Throwable) {
                loadingOverlay.visibility = View.GONE
                Toast.makeText(this@MainActivity, "서버 연결 실패. 인터넷을 확인하세요.", Toast.LENGTH_SHORT).show()
                isScanning = true // 실패했으니 다시 스캔할 수 있게 열어줌
            }
        })
    }

    private fun showSheet(sheet: ResultBottomSheet) {
        // 백그라운드 상태에서는 Fragment 를 띄울 수 없음 → 보관했다가 onPostResume 에서 표시
        if (supportFragmentManager.isStateSaved) {
            pendingSheet = sheet
            return
        }
        // 이미 떠 있으면 중복 표시 방지
        if (supportFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) != null) return

        sheet.show(supportFragmentManager, ResultBottomSheet.TAG)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanner.close()
    }

    companion object {
        private const val SAME_URL_COOLDOWN_MS = 3_000L
    }
}