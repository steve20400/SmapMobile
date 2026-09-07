package cm.smap.collector.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import cm.smap.collector.R
import cm.smap.collector.service.CollectService
import cm.smap.collector.ui.screens.*

/* ═══ Jetons de couleur SMAP (voir documentation, chap. design) ═══ */
object SmapColors {
    val Green = Color(0xFF1B6B4A)
    val GreenLight = Color(0xFF2E9E5B)
    val Ocre = Color(0xFFE8A33D)
    val Bg = Color(0xFF12140F)          // sombre par défaut : terrain, OLED
    val Surface = Color(0xFF1C1F19)
    val Surface2 = Color(0xFF252A22)
    val Line = Color(0xFF333A2E)
    val Ink = Color(0xFFF4F5F0)
    val Ink2 = Color(0xFFA7AE9C)
    val Ink3 = Color(0xFF6E7566)
    val Ok = Color(0xFF0CA30C)
    val Warn = Color(0xFFFAB219)
    val Crit = Color(0xFFD03B3B)
}

class MainActivity : ComponentActivity() {

    /** La langue choisie est appliquée avant toute résolution de ressource :
     *  chaque `stringResource` de l'application en hérite sans rien faire. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Langue.applique(newBase))
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS))

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = SmapColors.Green,
                    secondary = SmapColors.Ocre,
                    background = SmapColors.Bg,
                    surface = SmapColors.Surface,
                    onPrimary = Color.White,
                    onBackground = SmapColors.Ink,
                    onSurface = SmapColors.Ink,
                )
            ) {
                val nav = rememberNavController()
                Scaffold(
                    containerColor = SmapColors.Bg,
                    bottomBar = {
                        NavigationBar(containerColor = SmapColors.Surface) {
                            val entry by nav.currentBackStackEntryAsState()
                            val route = entry?.destination?.route
                            listOf(
                                "home" to R.string.nav_home,
                                "collect" to R.string.nav_collect,
                                "map" to R.string.nav_map,
                                "sync" to R.string.nav_sync,
                                "settings" to R.string.nav_settings,
                            ).forEach { (r, label) ->
                                NavigationBarItem(
                                    selected = route == r,
                                    onClick = { nav.navigate(r) { launchSingleTop = true } },
                                    icon = {},
                                    label = { Text(stringResource(label)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedTextColor = SmapColors.GreenLight,
                                        unselectedTextColor = SmapColors.Ink3,
                                        indicatorColor = SmapColors.Surface2))
                            }
                        }
                    }
                ) { pad ->
                    NavHost(nav, startDestination = "home",
                            modifier = Modifier.padding(pad).fillMaxSize()) {
                        composable("home") { HomeScreen(nav) }
                        composable("collect") { CollectScreen(nav) }
                        composable("survey") { SurveyScreen(nav) }
                        composable("property") { PropertyScreen(nav) }
                        composable("map") { MapScreen() }
                        composable("sync") { SyncScreen() }
                        composable("settings") { SettingsScreen(nav) }
                        // Un seul écran de scan, deux usages : poser une ancre
                        // sur le terrain, ou récupérer l'adresse du serveur
                        // affichée par la console. C'est la même caméra et le
                        // même décodeur, seule l'action qui suit change.
                        composable("scan/{mode}") { entree ->
                            ScanScreen(entree.arguments?.getString("mode") ?: "ancre")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // le service de collecte, lui, continue — c'est le but
    }
}

/** Démarre / arrête le service de collecte depuis les écrans. */
fun ComponentActivity.sendServiceAction(action: String, extras: Intent.() -> Unit = {}) {
    startForegroundService(Intent(this, CollectService::class.java)
        .setAction(action).apply(extras))
}
