/*
 * Copyright (C) 2016 Pedro Paulo de Amorim
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.folioreader.android.sample

import android.Manifest
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eazypermissions.common.model.PermissionResult
import com.eazypermissions.coroutinespermission.PermissionManager
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.folioreader.Config
import com.folioreader.FolioReader
import com.folioreader.FolioReader.OnClosedListener
import com.folioreader.model.HighLight
import com.folioreader.model.HighLight.HighLightAction
import com.folioreader.model.locators.ReadLocator
import com.folioreader.model.locators.ReadLocator.Companion.fromJson
import com.folioreader.util.AppUtil.Companion.getSavedConfig
import com.folioreader.util.OnHighlightListener
import com.folioreader.util.ReadLocatorListener
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.*
import java.net.URL
import java.util.*

class HomeActivity : AppCompatActivity(), OnHighlightListener, ReadLocatorListener,
    OnClosedListener {

    private lateinit var folioReader: FolioReader

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        folioReader = FolioReader.get()
            .setOnHighlightListener(this)
            .setReadLocatorListener(this)
            .setOnClosedListener(this)
        highlightsAndSave

        findViewById<View>(R.id.btn_raw).setOnClickListener {
            var config =
                getSavedConfig(applicationContext)
            if (config == null) config = Config()
            config.allowedDirection = Config.AllowedDirection.VERTICAL_AND_HORIZONTAL
            folioReader.setConfig(config, true)
                .openBook(R.raw.accessible_epub_3)
        }

        findViewById<View>(R.id.btn_assest).setOnClickListener {
            val readLocator = lastReadLocator
            var config = getSavedConfig(applicationContext)
            if (config == null) config = Config()
            config!!.allowedDirection = Config.AllowedDirection.VERTICAL_AND_HORIZONTAL
            folioReader.setReadLocator(readLocator)
            folioReader.setConfig(config, true)
                .openBook("file:///android_asset/TheSilverChair.epub")
        }
        findViewById<View>(R.id.btn_external_url).setOnClickListener {
            val readLocator = lastReadLocator
            var config = getSavedConfig(applicationContext)
            if (config == null) config = Config()
            config?.allowedDirection = Config.AllowedDirection.VERTICAL_AND_HORIZONTAL
            folioReader.setReadLocator(readLocator)
            folioReader.setConfig(config, true)
            folioReader.openEpubFromNetwork("https://wolnelektury.pl/media/book/epub/balzac-komedia-ludzka-muza-z-zascianka.epub")
        }
    }

    private val lastReadLocator: ReadLocator?
        private get() {
            val jsonString =
                loadAssetTextAsString("Locators/LastReadLocators/last_read_locator_1.json")
            return fromJson(jsonString)
        }

    override fun saveReadLocator(readLocator: ReadLocator) {
        Log.i(
            LOG_TAG,
            "-> saveReadLocator -> " + readLocator.toJson()
        )
    }//You can do anything on successful saving highlight list

    /*
     * For testing purpose, we are getting dummy highlights from asset. But you can get highlights from your server
     * On success, you can save highlights to FolioReader DB.
     */
    private val highlightsAndSave: Unit
        private get() {
            Thread(Runnable {
                var highlightList: ArrayList<HighLight?>? = null
                val objectMapper = ObjectMapper()
                try {
                    highlightList = objectMapper.readValue(
                        loadAssetTextAsString("highlights/highlights_data.json"),
                        object :
                            TypeReference<List<HighlightData?>?>() {})
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                if (highlightList == null) {
                    folioReader!!.saveReceivedHighLights(highlightList) {
                        //You can do anything on successful saving highlight list
                    }
                }
            }).start()
        }

    private fun loadAssetTextAsString(name: String): String? {
        var `in`: BufferedReader? = null
        try {
            val buf = StringBuilder()
            val `is` = assets.open(name)
            `in` = BufferedReader(InputStreamReader(`is`))
            var str: String?
            var isFirst = true
            while (`in`.readLine().also { str = it } != null) {
                if (isFirst) isFirst = false else buf.append('\n')
                buf.append(str)
            }
            return buf.toString()
        } catch (e: IOException) {
            Log.e("HomeActivity", "Error opening asset $name")
        } finally {
            if (`in` != null) {
                try {
                    `in`.close()
                } catch (e: IOException) {
                    Log.e("HomeActivity", "Error closing asset $name")
                }
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        FolioReader.clear()
    }

    override fun onHighlight(highlight: HighLight, type: HighLightAction) {
        Toast.makeText(
            this,
            "highlight id = " + highlight.uuid + " type = " + type,
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onFolioReaderClosed() {
        Log.v(LOG_TAG, "-> onFolioReaderClosed")
    }

    private fun FolioReader.openEpubFromNetwork(url: String) {
        ensurePermissions {
            GlobalScope.launch {
                url.downloadFileAndThen { path ->
                    handler.post {
                        openBook(path)
                    }
                }
            }
        }
    }

    private fun String.downloadFileAndThen(completion: (filePath: String) -> Unit) {
        val url = this
        try {
            val context = ContextWrapper(applicationContext)
            val rootDirectory = context.getExternalFilesDir(null) ?: run {
                Timber.w("failed to get root directory")
                return
            }
            val targetDirectory = rootDirectory.path + "/audioPliki"
            if (!File(targetDirectory).exists()) {
                File(targetDirectory).mkdir()
            }
            val fileName = Uri.parse(url).lastPathSegment ?: "unnamed.epub"
            Timber.d("file path $fileName")
            val targetFilePath = "$targetDirectory/$fileName"
            val myFile = File(targetFilePath)
            if (!myFile.exists()) {
                url.saveTo(targetFilePath)
            }
            completion.invoke(targetFilePath)
            Timber.d("file saved")

        } catch (e: Exception) {
            Timber.e("failed to make this happed $e")
            e.printStackTrace()
        }
    }

    private fun String.saveTo(path: String) {
        URL(this).openStream().use { input ->
            FileOutputStream(File(path)).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun AppCompatActivity.ensurePermissions(continuation: () -> Unit) {
        val activity = this
        val permissions = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        GlobalScope.launch {
            val result =
                PermissionManager.requestPermissions(
                    activity, 14, *permissions
                )
            when (result) {
                is PermissionResult.PermissionGranted -> {
                    continuation.invoke()
                }
                is PermissionResult.PermissionDenied,
                is PermissionResult.ShowRational,
                is PermissionResult.PermissionDeniedPermanently -> {
                    Timber.w("failed to obtain permissions $result")
                    finish()
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = HomeActivity::class.java.simpleName
    }
}


