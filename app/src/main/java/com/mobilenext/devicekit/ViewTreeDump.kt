package com.mobilenext.devicekit

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log

class ViewTreeDump : Instrumentation() {

    companion object {
        const val TAG = "ViewTreeDump"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val waitUntilIdle = arguments?.getString("waitUntilIdle")?.toLongOrNull() ?: 0L
        Thread {
            val uiAutomation = uiAutomation
            try {
                UiAutomationFactory.configureForWindowRetrieval(uiAutomation)

                val json = UiTreeSerializer.dump(uiAutomation, waitUntilIdle)
                Log.i(TAG, "JSON size: ${json.length} chars")

                val result = Bundle()
                result.putString("json", json)
                sendStatus(0, result)
                finish(Activity.RESULT_OK, Bundle())
            } catch (t: Throwable) {
                Log.e(TAG, "dump failed", t)
                val error = Bundle()
                error.putString("error", t.message ?: t.javaClass.simpleName)
                sendStatus(1, error)
                finish(Activity.RESULT_CANCELED, error)
            }
        }.start()
    }
}
