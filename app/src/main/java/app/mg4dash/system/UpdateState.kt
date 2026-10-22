package app.mg4dash.system

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Étapes du bouton « Buscar actualización » d'Ajustes (cockpit) : ce qu'affiche la ligne d'état et
 * ce que fait le bouton. Un premier toque cherche, un second installe la version trouvée.
 */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    /** Le dépôt n'a encore aucune version publiée avec un APK. */
    object NoRelease : UpdateState()
    data class Available(val release: Updater.Release) : UpdateState()
    data class Downloading(val version: String, val pct: Int) : UpdateState()
    data class Installing(val version: String) : UpdateState()
    data class Failed(val reason: String) : UpdateState()

    /** Le bouton répond (pas pendant une recherche, un téléchargement ou une installation). */
    val actionable: Boolean get() = this !is Checking && this !is Downloading && this !is Installing

    fun button(): String = when (this) {
        is Checking -> "Buscando…"
        is Available -> "Instalar ${release.version}"
        is Downloading, is Installing -> "Actualizando…"
        else -> "Buscar actualización"
    }

    fun status(installed: String): String = when (this) {
        is Idle -> "Versión $installed"
        is Checking -> "Versión $installed · buscando en GitHub…"
        is UpToDate -> "Versión $installed · es la última"
        is NoRelease -> "Versión $installed · aún no hay versiones publicadas"
        is Available -> "Nueva versión ${release.version}" + firstNote(release.notes)?.let { " · $it" }.orEmpty()
        is Downloading -> "Descargando ${version} · $pct %"
        is Installing -> "Instalando ${version} · MG4Dash se reiniciará"
        is Failed -> "Versión $installed · $reason"
    }

    companion object {
        /** Première nouveauté des notes de version (les titres markdown sont sautés, la puce retirée). */
        fun firstNote(md: String): String? = Updater.plainNotes(md).lines()
            .map { it.trim() }.filterNot { it.startsWith("#") }
            .map { it.removePrefix("•").trim() }
            .firstOrNull { it.isNotEmpty() }

        /** Raison lisible d'un échec : sans données dans la SIM de la voiture, le cas le plus courant est l'absence de réseau. */
        fun reason(e: Throwable): String = when (e) {
            is UnknownHostException, is ConnectException -> "sin conexión a Internet"
            is SocketTimeoutException -> "GitHub no responde"
            is Updater.BadDownload -> e.message ?: "la descarga no es válida"
            is Updater.HttpError -> if (e.code == 403 || e.code == 429) "GitHub limita las consultas, prueba más tarde" else "GitHub responde ${e.code}"
            is IOException -> "error de red: ${e.message ?: e.javaClass.simpleName}"
            else -> "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
