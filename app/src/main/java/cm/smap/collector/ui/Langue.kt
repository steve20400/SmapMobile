package cm.smap.collector.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Choix de langue de l'application, indépendant de celui du téléphone.
 *
 * Pourquoi ne pas simplement suivre la langue du système : les agents de
 * terrain partagent parfois un même téléphone, et un partenaire anglophone
 * doit pouvoir basculer l'application sans changer les réglages de l'appareil
 * entier. Le choix est donc porté par l'application et conservé localement.
 *
 * La bascule passe par la configuration du contexte plutôt que par
 * AppCompatDelegate : l'application n'utilise pas AppCompat (les activités
 * dérivent de ComponentActivity), et cette voie fonctionne dès Android 8,
 * soit le minimum visé — les téléphones d'entrée de gamme du terrain.
 */
object Langue {

    /** Codes ISO des langues proposées. Le français reste la langue par défaut. */
    const val FR = "fr"
    const val EN = "en"

    private const val PREFS = "smap"
    private const val CLE = "langue"

    fun courante(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLE, FR) ?: FR

    fun definir(ctx: Context, code: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CLE, code).apply()
    }

    /**
     * Renvoie un contexte dont la configuration porte la langue choisie.
     *
     * Appelé depuis `attachBaseContext` : toutes les ressources résolues
     * ensuite — y compris celles lues par `stringResource` dans Compose —
     * suivent ce choix, sans que chaque écran ait à s'en soucier.
     */
    fun applique(base: Context): Context {
        val locale = Locale.forLanguageTag(courante(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
