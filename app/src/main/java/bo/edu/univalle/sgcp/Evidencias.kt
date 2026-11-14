package bo.edu.univalle.sgcp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Evidencias en almacenamiento privado de la app (RNF-03): `files/evidencias`.
 * No van a la galería, así que otras aplicaciones no las ven.
 */
object Evidencias {
    const val MAXIMO_BYTES = 500 * 1024
    const val LADO_MAXIMO = 1600

    fun carpeta(context: Context): File = File(context.filesDir, "evidencias").apply { mkdirs() }

    fun nuevaFoto(context: Context): File = File(carpeta(context), "foto_${UUID.randomUUID()}.jpg")

    /** Reduce la foto a JPEG de hasta 500 KiB (la API acepta hasta 512 KiB por imagen) y la reescribe en su lugar. */
    fun reducir(archivo: File): Boolean {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(archivo.path, limites)
        if (limites.outWidth <= 0) return false
        var muestra = 1
        while (maxOf(limites.outWidth, limites.outHeight) / muestra > LADO_MAXIMO) muestra *= 2
        val imagen = BitmapFactory.decodeFile(archivo.path, BitmapFactory.Options().apply { inSampleSize = muestra }) ?: return false
        var calidad = 85
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().also { imagen.compress(Bitmap.CompressFormat.JPEG, calidad, it) }.toByteArray()
            calidad -= 10
        } while (bytes.size > MAXIMO_BYTES && calidad >= 25)
        imagen.recycle()
        if (bytes.size > MAXIMO_BYTES) return false
        archivo.writeBytes(bytes)
        return true
    }
}

/** Reglas de formato del proyecto: cantidades con tres decimales y punto; fechas UTC al enviar, Bolivia al mostrar. */
object Formato {
    val BOLIVIA: ZoneOffset = ZoneOffset.ofHours(-4)
    private val PANTALLA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    /** Acepta «12,5» o «12.5» y devuelve «12.500». Null si no es positivo o tiene más de tres decimales. */
    fun peso(texto: String): String? {
        val valor = texto.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        if (valor <= BigDecimal.ZERO || valor.stripTrailingZeros().scale() > 3 || valor > BigDecimal("999999999")) return null
        return valor.setScale(3, RoundingMode.UNNECESSARY).toPlainString()
    }

    /** «dd/MM/yyyy HH:mm» en hora de Bolivia a ISO UTC con Z, como pide la API. */
    fun fechaUtc(textoBolivia: String): String? = runCatching {
        LocalDateTime.parse(textoBolivia.trim(), PANTALLA).toInstant(BOLIVIA).toString()
    }.getOrNull()

    fun ahoraBolivia(): String = PANTALLA.format(LocalDateTime.now(BOLIVIA))

    fun bolivia(iso: String?): String = if (iso.isNullOrBlank()) "—" else runCatching {
        val instante = if (iso.endsWith("Z") || iso.contains('+')) Instant.parse(iso) else Instant.parse(iso + "Z")
        PANTALLA.format(instante.atOffset(BOLIVIA))
    }.getOrDefault(iso)
}
