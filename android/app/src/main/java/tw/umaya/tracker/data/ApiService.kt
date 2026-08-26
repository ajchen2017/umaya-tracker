package tw.umaya.tracker.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val token: String, val user: UserDto)
data class UserDto(val id: Long, val email: String, val displayName: String)

data class RegisterRequest(val email: String, val password: String, val displayName: String)

data class CreateHikeRequest(val name: String)
data class HikeDto(val id: Long, val name: String, val shareToken: String, val status: String)

data class UploadPointDto(
    val clientId: String,
    val lat: Double,
    val lng: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val markerType: String,
    val batteryPct: Int?,
    val recordedAt: String,
)
data class UploadPointsRequest(val points: List<UploadPointDto>)
data class UploadPointsResponse(val inserted: Int, val skipped: Int)

interface ApiService {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<UserDto>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("hikes")
    suspend fun createHike(
        @Header("Authorization") bearer: String,
        @Body body: CreateHikeRequest,
    ): Response<HikeDto>

    @PATCH("hikes/{id}/end")
    suspend fun endHike(
        @Header("Authorization") bearer: String,
        @Path("id") hikeId: Long,
    ): Response<HikeDto>

    @POST("hikes/{id}/points")
    suspend fun uploadPoints(
        @Header("Authorization") bearer: String,
        @Path("id") hikeId: Long,
        @Body body: UploadPointsRequest,
    ): Response<UploadPointsResponse>
}
