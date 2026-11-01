package app.nota

import android.app.Application
import app.nota.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class NotaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@NotaApplication)
            modules(appModule)
        }
    }
}
