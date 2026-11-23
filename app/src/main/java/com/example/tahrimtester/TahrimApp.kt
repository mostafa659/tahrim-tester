package com.example.tahrimtester

import ai.quranlab.tajweedformer.TajweedClient
import android.app.Application

/** عميل واحد فقط لكل عملية، و«مُرتِّل» واحد يحمل حالة التلاوة ويبقى حياً مع دوران الشاشة. */
class TahrimApp : Application() {
    val client: TajweedClient by lazy { TajweedClient(this, BuildConfig.QURANLAB_KEY) }
    val reciter: Reciter by lazy { Reciter(this) }
}
