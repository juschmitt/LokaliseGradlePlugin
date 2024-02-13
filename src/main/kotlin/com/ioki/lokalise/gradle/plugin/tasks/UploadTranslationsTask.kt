package com.ioki.lokalise.gradle.plugin.tasks

import com.ioki.lokalise.api.models.FileUpload
import com.ioki.lokalise.gradle.plugin.LokaliseExtension
import com.ioki.lokalise.gradle.plugin.internal.FileInfo
import com.ioki.lokalise.gradle.plugin.internal.LokaliseApi
import com.ioki.lokalise.gradle.plugin.internal.LokaliseApiBuildService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal abstract class UploadTranslationsTask : DefaultTask() {

    @get:Internal
    abstract val lokaliseApi: Property<LokaliseApi>

    @get:Input
    @get:Optional
    abstract val pollUploadProcess: Property<Boolean>

    @get:Input
    abstract val translationFilesToUpload: Property<ConfigurableFileTree>

    @get:Input
    abstract val params: MapProperty<String, Any>

    @TaskAction
    fun f() {
        runBlocking {
            translationFilesToUpload.get()
                .toFileInfo()
                .also {
                    logger.log(
                        LogLevel.INFO,
                        "Execute uploading file with the following params:\n" +
                            "${params.get()}\n" +
                            "and the following file info:\n" +
                            "$it"
                    )
                }
                .uploadEach(lokaliseApi.get(), params.get("lang_iso").toString(), params.remove("lang_iso"))
                .run { if(pollUploadProcess.get()) checkProcess(lokaliseApi.get()) }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ConfigurableFileTree.toFileInfo(): List<FileInfo> = map {
        val fileName = it.path.replace(dir.absolutePath, ".")
        val base64FileContent = Base64.encode(it.readBytes())
        FileInfo(fileName, base64FileContent)
    }

    private suspend fun List<FileInfo>.uploadEach(
        lokaliseApi: LokaliseApi,
        langIso: String,
        params: Map<String, Any>,
    ): List<FileUpload> = withContext(Dispatchers.IO) {
        lokaliseApi.uploadFiles(this@uploadEach, langIso, params)
    }

    private suspend fun List<FileUpload>.checkProcess(lokaliseApi: LokaliseApi) = withContext(Dispatchers.IO) {
        lokaliseApi.checkProcess(this@checkProcess)
    }
}

internal fun TaskContainer.registerUploadTranslationTask(
    lokaliseService: Provider<LokaliseApiBuildService>,
    lokaliseExtensions: LokaliseExtension,
): TaskProvider<UploadTranslationsTask> = register("uploadTranslations", UploadTranslationsTask::class.java) {
    it.usesService(lokaliseService)
    it.lokaliseApi.set(lokaliseService)
    it.translationFilesToUpload.set(lokaliseExtensions.uploadStringsConfig.translationsFilesToUpload)
    it.params.set(lokaliseExtensions.uploadStringsConfig.params)
}

private fun MapProperty<String, Any>.get(key: String): Any =
    get().getOrElse(key) { throw GradleException("Value for key(=$key) not found") }

private fun MapProperty<String, Any>.remove(key: String): Map<String, Any> =
    get().toMutableMap().apply { remove(key) }
