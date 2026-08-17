package com.s4nj1th.slauncher.helper

import com.s4nj1th.slauncher.data.AppModel

interface AppFilterHelper {
    fun onAppFiltered(items:List<AppModel>)
}