package com.example.tahrimtester

import android.app.Application
import ai.quranlab.tajweedformer.TajweedClient

/** عميل واحد فقط لكل عملية (client_exists إن أُنشئ ثانٍ والأول مفتوح). */
class TahrimApp : Application() {
    val client: TajweedClient by lazy { TajweedClient(this, BuildConfig.QURANLAB_KEY) }
}
