package com.example.safeqr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private var isScanning = true // 한 번 스캔되면 멈추게 하는 스위치

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
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
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

            // QR 코드만 빠르게 찾도록 옵션 설정
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()

            val scanner = BarcodeScanning.getClient(options)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        // URL 형태(http)의 QR코드인지 확인
                        // URL 형태(http)의 QR코드인지 확인
                        if (rawValue != null && rawValue.startsWith("http")) {
                            isScanning = false // 중복 스캔 방지
                            Toast.makeText(this@MainActivity, "스캔 완료! 안전성 검사 중...", Toast.LENGTH_SHORT).show()

                            // 1. 스캔한 URL을 데이터 상자에 담기
                            val requestData = AnalyzeRequest(rawValue)

                            // 2. 백엔드 서버로 택배 보내기 (비동기 통신)
                            NetworkManager.api.analyzeUrl(requestData).enqueue(object : retrofit2.Callback<AnalyzeResponse> {
                                override fun onResponse(
                                    call: retrofit2.Call<AnalyzeResponse>,
                                    response: retrofit2.Response<AnalyzeResponse>
                                ) {
                                    if (response.isSuccessful) {
                                        val result = response.body()
                                        if (result != null) {
                                            // 3. 서버에서 결과가 무사히 도착함!
                                            val level = result.level // safe, warning, danger 중 하나
                                            val score = result.score

                                            Toast.makeText(this@MainActivity, "분석 완료! 상태: $level (위험도: ${score}점)", Toast.LENGTH_LONG).show()

                                            // TODO: 다음 단계에서 이 level을 보고 바텀 시트 색상을 바꾸고 브라우저를 띄울 겁니다!
                                        }
                                    } else {
                                        Toast.makeText(this@MainActivity, "서버 응답 오류 (코드: ${response.code()})", Toast.LENGTH_SHORT).show()
                                        isScanning = true // 실패했으니 다시 스캔할 수 있게 열어줌
                                    }
                                }

                                override fun onFailure(call: retrofit2.Call<AnalyzeResponse>, t: Throwable) {
                                    Toast.makeText(this@MainActivity, "서버 연결 실패. 인터넷을 확인하세요.", Toast.LENGTH_SHORT).show()
                                    isScanning = true // 실패했으니 다시 스캔할 수 있게 열어줌
                                }
                            })
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close() // 다음 프레임 분석을 위해 반드시 닫아주기
                }
        } else {
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}