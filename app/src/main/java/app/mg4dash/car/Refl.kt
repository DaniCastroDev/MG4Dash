package app.mg4dash.car

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Méthodes des SDK SAIC / Android résolues une seule fois par classe, nom et nombre d'arguments :
 * la relecture de la clim passe à 4 fois par seconde et getMethod() à chaque appel coûtait cher
 * sur le MT2712.
 */
internal object Refl {
    private val cache = ConcurrentHashMap<String, Method>()

    fun m(target: Any, name: String, vararg params: Class<*>?): Method {
        val cls = target.javaClass
        val key = cls.name + '#' + name + '/' + params.size
        return cache[key] ?: cls.getMethod(name, *params).also { cache[key] = it }
    }
}
