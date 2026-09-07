package cm.smap.collector

import android.app.Application
import cm.smap.collector.core.DeviceKeys
import cm.smap.collector.core.SmapApi
import cm.smap.collector.data.SmapDb
import cm.smap.collector.work.SyncWorker

/**
 * Racine de l'application — injection de dépendances manuelle et assumée :
 * trois singletons, zéro framework DI, zéro étape de génération de code.
 * (Hilt pourra être introduit plus tard si l'équipe grandit.)
 */
class SmapApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        DeviceKeys.ensureKey()
        db = SmapDb.get(this, DeviceKeys.dbPassphrase())
        api = SmapApi(this)
        SyncWorker.schedulePeriodic(this)
    }

    companion object {
        lateinit var instance: SmapApp; private set
        lateinit var db: SmapDb; private set
        lateinit var api: SmapApi; private set

        /** Dernier relevé 60 s terminé — proposé au formulaire de bien. */
        @Volatile var lastSurveyUuid: String? = null
        @Volatile var lastKnownLat: Double = 0.0
        @Volatile var lastKnownLon: Double = 0.0
    }
}
