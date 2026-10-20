package com.example.localnewsassistant

import android.app.Application
import com.example.localnewsassistant.data.local.NewsCountReader
import com.example.localnewsassistant.data.local.NewsDatabase
import com.example.localnewsassistant.data.local.NewsDatabaseFactory

class LocalNewsApplication : Application() {

    val database: NewsDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NewsDatabaseFactory.createDatabase(this)
    }

    val newsCountReader: NewsCountReader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NewsCountReader(
            daoProvider = { database.newsDao() }
        )
    }
}
