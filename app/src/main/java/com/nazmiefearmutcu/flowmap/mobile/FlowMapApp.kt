package com.nazmiefearmutcu.flowmap.mobile

import android.app.Application
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph

class FlowMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
