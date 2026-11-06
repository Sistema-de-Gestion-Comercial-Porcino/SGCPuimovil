package bo.edu.univalle.sgcp

import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

data class AccesoPeticion(val login: String, val clave: String)
data class Perfil(val id: String, val nombre: String, val roles: List<String>, val permisos: List<String>)
data class AccesoRespuesta(val token: String, val expira: String, val usuario: Perfil)
data class ErrorApi(val codigo: String?, val mensaje: String?)
interface ServicioApi {
    @POST("auth/login") fun acceder(@Body peticion: AccesoPeticion): Call<AccesoRespuesta>
    @GET("auth/yo") fun perfil(@Header("Authorization") token: String): Call<Perfil>
    @POST("auth/cerrar-sesion") fun salir(@Header("Authorization") token: String): Call<Void>
}
object Conexion {
    fun validar(raw: String, desarrollo: Boolean): String {
        val uri = URI(raw.trim())
        require(uri.host != null 
            && uri.userInfo == null
            && uri.query == null
            && uri.fragment == null) { "Escribe una dirección válida del servidor." }
        require(uri.scheme == "https" || desarrollo && uri.scheme == "http") { "La conexión requiere HTTPS." }
        require(uri.path.trimEnd('/').endsWith("/api/v1")) { "La dirección debe terminar en /api/v1/." }
        return raw.trim().trimEnd('/') + "/"
    }
    fun crear(url: String): ServicioApi = Retrofit.Builder().baseUrl(validar(url, BuildConfig.DEBUG))
        .client(OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create()).build().create(ServicioApi::class.java)
    fun vencido(expira: String): Boolean = runCatching { Instant.parse(expira).isBefore(Instant.now()) }.getOrDefault(true)
}
