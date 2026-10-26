package com.example.localnewsassistant.tdlib

object TdlibNativeLoadProbe {
    fun load(): Boolean {
        System.loadLibrary("tdjni")
        return true
    }
}
