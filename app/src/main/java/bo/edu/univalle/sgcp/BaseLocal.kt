package bo.edu.univalle.sgcp

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/*
 * Almacenamiento local con Room (RNF-01). El teléfono guarda solo:
 *  - las entregas descargadas que tiene asignadas, para poder capturar sin señal;
 *  - las capturas de recepción y la ruta privada de su evidencia.
 * Nada de esto cambia la entrega, el inventario ni la deuda: eso lo decide el servidor.
 */

@Entity(tableName = "entregas")
data class EntregaLocal(
    @PrimaryKey val id: String,
    val version: Int,
    val pedido: String,
    val rutaId: String?,
    val cliente: String,
    val direccion: String,
    val telefono: String,
    val fechaPrevista: String,
    val productos: String,
    val descargadaEn: Long
)

@Entity(tableName = "capturas", indices = [Index("entregaId"), Index("estado")])
data class CapturaLocal(
    @PrimaryKey val clave: String,
    val entregaId: String,
    val versionBase: Int,
    val autorId: String,
    val fechaReal: String,
    val pesoRecibido: String,
    val unidad: String,
    val conforme: Boolean,
    val receptor: String,
    val observacion: String,
    val estado: String,
    val recepcionId: String?,
    val fechaSincronizacion: String?,
    val motivo: String?,
    val creadaEn: Long
)

@Entity(
    tableName = "evidencias",
    foreignKeys = [ForeignKey(entity = CapturaLocal::class, parentColumns = ["clave"], childColumns = ["clave"])],
    indices = [Index("clave")]
)
data class EvidenciaLocal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clave: String,
    val ruta: String,
    val orden: Int
)

@Dao
interface RepartoDao {
    @Query("SELECT * FROM entregas ORDER BY fechaPrevista, pedido")
    suspend fun entregas(): List<EntregaLocal>

    @Upsert
    suspend fun guardarEntregas(entregas: List<EntregaLocal>)

    /** Quita del teléfono las entregas que ya no están asignadas, salvo las que tienen captura. */
    @Query("DELETE FROM entregas WHERE id NOT IN (:vigentes) AND id NOT IN (SELECT entregaId FROM capturas)")
    suspend fun quitarNoAsignadas(vigentes: List<String>)

    @Transaction
    suspend fun reemplazarDescarga(entregas: List<EntregaLocal>) {
        quitarNoAsignadas(entregas.map { it.id })
        guardarEntregas(entregas)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertarCaptura(captura: CapturaLocal)

    @Insert
    suspend fun insertarEvidencias(evidencias: List<EvidenciaLocal>)

    @Transaction
    suspend fun guardarCaptura(captura: CapturaLocal, evidencias: List<EvidenciaLocal>) {
        insertarCaptura(captura)
        insertarEvidencias(evidencias)
    }

    @Query("SELECT * FROM capturas WHERE estado = :estado ORDER BY creadaEn, clave")
    suspend fun capturasEn(estado: String): List<CapturaLocal>

    @Query("SELECT * FROM capturas ORDER BY creadaEn")
    suspend fun capturas(): List<CapturaLocal>

    @Query("SELECT ruta FROM evidencias WHERE clave = :clave ORDER BY orden")
    suspend fun rutasEvidencia(clave: String): List<String>

    @Query("UPDATE capturas SET estado = :estado, recepcionId = :recepcionId, fechaSincronizacion = :fecha, motivo = :motivo WHERE clave = :clave")
    suspend fun actualizarEstado(clave: String, estado: String, recepcionId: String?, fecha: String?, motivo: String?)

    @Query("SELECT COUNT(*) FROM capturas WHERE estado IN ('PENDIENTE', 'ENVIANDO')")
    suspend fun sinEnviar(): Int
}

@Database(entities = [EntregaLocal::class, CapturaLocal::class, EvidenciaLocal::class], version = 1, exportSchema = true)
abstract class BaseLocal : RoomDatabase() {
    abstract fun reparto(): RepartoDao

    companion object {
        @Volatile private var instancia: BaseLocal? = null

        fun obtener(context: Context): BaseLocal = instancia ?: synchronized(this) {
            instancia ?: Room.databaseBuilder(context.applicationContext, BaseLocal::class.java, "sgcp_reparto.db")
                .build().also { instancia = it }
        }
    }
}

/** AlmacenPendientes sobre Room: la cola sobrevive al cierre de la aplicación. */
class AlmacenPendientesRoom(private val dao: RepartoDao) : AlmacenPendientes {
    override suspend fun guardar(registro: RegistroEnvio) {
        dao.guardarCaptura(
            CapturaLocal(
                clave = registro.clave,
                entregaId = registro.entregaId,
                versionBase = registro.versionBase,
                autorId = registro.autorId,
                fechaReal = registro.fechaReal,
                pesoRecibido = registro.pesoRecibido,
                unidad = registro.unidad,
                conforme = registro.conforme,
                receptor = registro.receptor,
                observacion = registro.observacion,
                estado = registro.estado,
                recepcionId = registro.recepcionId,
                fechaSincronizacion = registro.fechaSincronizacion,
                motivo = registro.motivo,
                creadaEn = registro.creadaEn
            ),
            registro.evidencias.mapIndexed { i, ruta -> EvidenciaLocal(clave = registro.clave, ruta = ruta, orden = i) }
        )
    }

    override suspend fun listar() = enEstado(Pendiente.nombre)
    override suspend fun enConflicto() = enEstado(Conflicto.nombre)
    override suspend fun enviando() = enEstado(Enviando.nombre)

    override suspend fun actualizar(sincronizacion: SincronizacionRecepcion) = dao.actualizarEstado(
        sincronizacion.clave,
        sincronizacion.estado.nombre,
        sincronizacion.recepcionId,
        sincronizacion.fechaSincronizacion,
        sincronizacion.motivo
    )

    suspend fun todas(): List<RegistroEnvio> = dao.capturas().map { registro(it) }

    private suspend fun enEstado(estado: String) = dao.capturasEn(estado).map { registro(it) }

    private suspend fun registro(c: CapturaLocal) = RegistroEnvio(
        clave = c.clave,
        entregaId = c.entregaId,
        versionBase = c.versionBase,
        autorId = c.autorId,
        fechaReal = c.fechaReal,
        pesoRecibido = c.pesoRecibido,
        unidad = c.unidad,
        conforme = c.conforme,
        receptor = c.receptor,
        observacion = c.observacion,
        evidencias = dao.rutasEvidencia(c.clave),
        estado = c.estado,
        recepcionId = c.recepcionId,
        fechaSincronizacion = c.fechaSincronizacion,
        motivo = c.motivo,
        creadaEn = c.creadaEn
    )
}
