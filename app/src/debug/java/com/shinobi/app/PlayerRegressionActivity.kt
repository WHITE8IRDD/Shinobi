package com.shinobi.app

import android.content.Context
import androidx.activity.ComponentActivity

/** Empty, locale-wrapped host for offline rotation/player regression tests (not in release). */
class PlayerRegressionActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))
}
