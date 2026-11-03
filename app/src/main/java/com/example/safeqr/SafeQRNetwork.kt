package com.example.safeqr

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// 1. 서버로 보낼 데이터 상자 (Request)
data class AnalyzeRequest(
    val url: String
)

// 2. 서버에서 받을 데이터 상자 (Response)
data class AnalyzeResponse(
    val url: String,
    val domain: String,
    val score: Int,
    val level: String, // "safe", "warning", "danger"
    val reasons: List<String>
)

// 3. 택배 기사(Retrofit)가 사용할 주소록
interface SafeQRApi {
    @POST("/analyze")
    fun analyzeUrl(@Body request: AnalyzeRequest): Call<AnalyzeResponse>
}

// 4. 택배 기사(Retrofit) 회사 설립
object NetworkManager {
    private const val BASE_URL = "https://safeqr.onrender.com" // 영채님이 만든 서버 주소

    // 서버가 Safe Browsing·WHOIS·페이지 접속을 함께 하므로 분석에 시간이 걸릴 수 있음.
    // 무료 서버(Render)가 잠들어 있다 깨어나는 시간까지 고려해 넉넉하게 기다린다.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)   // 서버 연결까지
        .readTimeout(60, TimeUnit.SECONDS)      // 분석 결과가 올 때까지
        .writeTimeout(15, TimeUnit.SECONDS)     // 요청 보내기
        .callTimeout(90, TimeUnit.SECONDS)      // 전체 최대 대기 시간
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: SafeQRApi = retrofit.create(SafeQRApi::class.java)
}