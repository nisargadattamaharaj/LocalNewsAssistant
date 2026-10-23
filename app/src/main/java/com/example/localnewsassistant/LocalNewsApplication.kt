package com.example.localnewsassistant

import android.app.Application
import com.example.localnewsassistant.data.local.NewsCountReader
import com.example.localnewsassistant.data.local.NewsDatabase
import com.example.localnewsassistant.data.local.NewsDatabaseFactory
import com.example.localnewsassistant.data.local.NewsSearchReader

class LocalNewsApplication : Application() {

    val database: NewsDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NewsDatabaseFactory.createDatabase(this)
    }

    val newsCountReader: NewsCountReader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NewsCountReader(
            daoProvider = { database.newsDao() }
        )
    }

    val newsSearchReader: NewsSearchReader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NewsSearchReader(
            daoProvider = { database.newsDao() }
        )
    }
}
