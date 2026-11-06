package bo.edu.univalle.sgcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SesionGuardada(val token: String, val expira: String, val nombre: String, val url: String)
class AlmacenSesion(context: Context) {
    private val datos = context.getSharedPreferences("sesion", Context.MODE_PRIVATE)
    private val alias = "sgcp.sesion"
    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun guardar(sesion: SesionGuardada) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, clave()) }
        val contenido = cipher.doFinal(Gson().toJson(sesion).toByteArray(Charsets.UTF_8))
        check(datos.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("contenido", Base64.encodeToString(contenido, Base64.NO_WRAP)).commit()) { "No se pudo guardar la sesión." }
    }
    fun leer(): SesionGuardada? = runCatching {
        val iv = datos.getString("iv", null) ?: return null
        val contenido = datos.getString("contenido", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,
                clave(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        Gson().fromJson(String(cipher.doFinal(Base64.decode(contenido, Base64.NO_WRAP)), Charsets.UTF_8), SesionGuardada::class.java)
    }.getOrNull()
    fun borrar() { datos.edit().remove("iv").remove("contenido").apply() }
}
