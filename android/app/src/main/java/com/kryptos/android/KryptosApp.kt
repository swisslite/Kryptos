package com.kryptos.android

import android.app.Application
import com.kryptos.android.store.SecureStore
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class KryptosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SecureStore.init(this)
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }
}
