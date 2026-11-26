package bo.edu.univalle.sgcp

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Reglas de presentación de la app, separadas de las pantallas para poder probarlas sin Android.
 * Los colores son los de la web (SGCPuiweb, site.css) para que las dos interfaces se vean como un solo sistema.
 */
object Paleta {
    const val VERDE = 0xFF174B3E.toInt()
    const val VERDE_OSCURO = 0xFF0F3B31.toInt()
    const val CREMA = 0xFFF5F1E7.toInt()
    const val CREMA_HONDA = 0xFFEEE8DC.toInt()
    const val PAPEL = 0xFFFFFEFA.toInt()
    const val LINEA = 0xFFD8D4C9.toInt()
    const val TINTA = 0xFF102E28.toInt()
    const val TINTA_SUAVE = 0xFF2F4640.toInt()
    const val GRIS = 0xFF69776F.toInt()
    const val SALVIA = 0xFF8DAA94.toInt()
    const val SALVIA_SUAVE = 0xFFE6ECE3.toInt()
    const val ARCILLA = 0xFFB86643.toInt()
    const val ARCILLA_OSCURA = 0xFF9F4F32.toInt()
    const val PELIGRO = 0xFFA83A38.toInt()
    const val AVISO = 0xFF8A5A12.toInt()
}

/** Tono de una etiqueta de estado: color de fondo suave y color de texto fuerte. */
enum class Tono(val fondo: Int, val texto: Int) {
    NEUTRO(Paleta.CREMA_HONDA, Paleta.TINTA_SUAVE),
    EXITO(Paleta.SALVIA_SUAVE, Paleta.VERDE),
    AVISO(0xFFF6EBD6.toInt(), Paleta.AVISO),
    OBSERVADA(0xFFF5E3DA.toInt(), Paleta.ARCILLA_OSCURA),
    PELIGRO(0xFFF6DEDC.toInt(), Paleta.PELIGRO)
}

/**
 * ENTREGADA y OBSERVADA son entregas que ocurrieron: se ven distintas, pero ninguna como error.
 * Solo el conflicto usa el tono de peligro, porque necesita que una persona lo revise.
 */
fun tonoEntrega(estado: EstadoEnvio?, conforme: Boolean?): Tono = when (estado) {
    null -> Tono.NEUTRO
    Confirmada -> if (conforme == true) Tono.EXITO else Tono.OBSERVADA
    Conflicto -> Tono.PELIGRO
    Descartada -> Tono.NEUTRO
    else -> Tono.AVISO
}

/**
 * Quién usa la app: cualquiera con permiso para administrar entregas (ENTREGA y los perfiles de gestión que pueden
 * ser responsables de una entrega). Se decide por el permiso y no por el nombre del rol: el rol ADMINISTRACION
 * desapareció con los perfiles jerárquicos y la app había quedado cerrada para ellos.
 */
fun puedeRepartir(perfil: Perfil): Boolean = "entregas.administrar" in perfil.permisos || "ENTREGA" in perfil.roles

object Enlaces {
    /** «tel:» con solo dígitos y un «+» inicial. Null si no queda un número marcable. */
    fun telefono(texto: String?): String? {
        if (texto.isNullOrBlank()) return null
        val limpio = texto.trim().let { t -> (if (t.startsWith("+")) "+" else "") + t.filter(Char::isDigit) }
        return if (limpio.count(Char::isDigit) >= 6) "tel:$limpio" else null
    }

    /** Búsqueda de la dirección en la app de mapas. Si la dirección no nombra la ciudad, se agrega Santa Cruz. */
    fun mapa(direccion: String?): String? {
        if (direccion.isNullOrBlank()) return null
        val consulta = if (direccion.contains("Santa Cruz", ignoreCase = true)) direccion.trim() else direccion.trim() + ", Santa Cruz, Bolivia"
        return "geo:0,0?q=" + URLEncoder.encode(consulta, "UTF-8").replace("+", "%20")
    }
}

object FechasPantalla {
    private val PANTALLA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    /** Lo que devuelven los selectores de fecha y hora (mes de 0 a 11, como Android) en el formato del formulario. */
    fun desdeSelector(anio: Int, mesBase0: Int, dia: Int, hora: Int, minuto: Int): String =
        PANTALLA.format(LocalDateTime.of(anio, mesBase0 + 1, dia, hora, minuto))

    /** Fecha escrita en el formulario, o ahora en Bolivia si no se puede leer: es el punto de partida del selector. */
    fun paraSelector(texto: String): LocalDateTime =
        runCatching { LocalDateTime.parse(texto.trim(), PANTALLA) }.getOrElse { LocalDateTime.now(Formato.BOLIVIA) }

    /** Momento de una captura (milisegundos del teléfono) en hora de Bolivia. */
    fun capturada(milisegundos: Long): String = PANTALLA.format(Instant.ofEpochMilli(milisegundos).atOffset(Formato.BOLIVIA))
}
