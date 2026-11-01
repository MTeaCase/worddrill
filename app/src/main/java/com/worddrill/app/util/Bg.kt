package com.worddrill.app.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** 所有数据库操作走单线程队列，结果回主线程。 */
object Bg {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "word-drill-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(work)
            main.post { done(result) }
        }
    }

    fun post(action: () -> Unit) {
        main.post(action)
    }
}
