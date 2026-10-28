package com.dev.launcher.helper

import com.dev.launcher.data.AppModel

interface AppFilterHelper {
    fun onAppFiltered(items:List<AppModel>)
}