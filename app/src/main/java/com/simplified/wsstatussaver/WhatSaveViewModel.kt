/*
 * Copyright (C) 2023 Christians Martínez Alvarado
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 * the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package com.simplified.wsstatussaver

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.lifecycle.*
import com.simplified.wsstatussaver.extensions.getMediaStoreUris
import com.simplified.wsstatussaver.extensions.getUri
import com.simplified.wsstatussaver.extensions.lastUpdateId
import com.simplified.wsstatussaver.extensions.preferences
import com.simplified.wsstatussaver.mediator.WAClient
import com.simplified.wsstatussaver.mediator.WAMediator
import com.simplified.wsstatussaver.model.Country
import com.simplified.wsstatussaver.model.Status
import com.simplified.wsstatussaver.model.StatusType
import com.simplified.wsstatussaver.mvvm.DeletionResult
import com.simplified.wsstatussaver.mvvm.SaveResult
import com.simplified.wsstatussaver.repository.Repository
import com.simplified.wsstatussaver.storage.Storage
import com.simplified.wsstatussaver.storage.StorageDevice
import com.simplified.wsstatussaver.update.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.*

class WhatSaveViewModel(
    private val repository: Repository,
    private val updateService: UpdateService,
    private val mediator: WAMediator,
    private val storage: Storage
) : ViewModel() {

    private val liveDataMap = newStatusesLiveDataMap()
    private val savedLiveDataMap = newStatusesLiveDataMap()

    private val installedClients = MutableLiveData<List<WAClient>>()
    private val storageDevices = MutableLiveData<List<StorageDevice>>()
    private val countries = MutableLiveData<List<Country>>()
    private val selectedCountry = MutableLiveData<Country>()

    override fun onCleared() {
        super.onCleared()
        liveDataMap.clear()
        savedLiveDataMap.clear()
    }

    fun getInstalledClients(): LiveData<List<WAClient>> = installedClients

    fun getStorageDevices(): LiveData<List<StorageDevice>> = storageDevices

    fun getCountriesObservable(): LiveData<List<Country>> = countries

    fun getSelectedCountryObservable(): LiveData<Country> = selectedCountry

    fun getCountries() = countries.value ?: arrayListOf()

    fun getSelectedCountry() = selectedCountry.value

    fun getStatuses(type: StatusType): LiveData<List<Status>> {
        return liveDataMap.getOrCreateLiveData(type)
    }

    fun getSavedStatuses(type: StatusType): LiveData<List<Status>> {
        return savedLiveDataMap.getOrCreateLiveData(type)
    }

    fun loadClients() = viewModelScope.launch(IO) {
        val result = mediator.getInstalledClients()
        installedClients.postValue(result)
    }

    fun loadStorageDevices() = viewModelScope.launch(IO) {
        val result = storage.getStorageVolumes()
        storageDevices.postValue(result)
    }

    fun loadCountries() = viewModelScope.launch(IO) {
        val result = repository.allCountries()
        countries.postValue(result)
    }

    fun loadSelectedCountry() = viewModelScope.launch(IO) {
        selectedCountry.postValue(repository.defaultCountry())
    }

    fun setSelectedCountry(country: Country) = viewModelScope.launch(IO) {
        repository.defaultCountry(country)
        selectedCountry.postValue(country)
    }

    fun loadStatuses(type: StatusType) = viewModelScope.launch(IO) {
        liveDataMap[type]?.postValue(repository.statuses(type))
    }

    fun loadSavedStatuses(type: StatusType) = viewModelScope.launch(IO) {
        savedLiveDataMap[type]?.postValue(repository.savedStatuses(type))
    }

    fun saveStatus(status: Status, saveName: String? = null): LiveData<SaveResult> = liveData(IO) {
        emit(SaveResult(isSaving = true))
        val result = repository.saveStatus(status, saveName)
        emit(SaveResult.single(status, result))
    }

    fun saveStatuses(statuses: List<Status>): LiveData<SaveResult> = liveData(IO) {
        emit(SaveResult(isSaving = true))
        val result = repository.saveStatuses(statuses)
        val savedStatuses = result.keys.toList()
        val savedUris = result.values.toList()
        emit(SaveResult(statuses = savedStatuses, uris = savedUris, saved = result.size))
    }

    fun deleteStatus(status: Status): LiveData<DeletionResult> = liveData(IO) {
        emit(DeletionResult(isDeleting = true))
        val result = repository.deleteStatus(status)
        emit(DeletionResult.single(status, result))
    }

    fun deleteStatuses(statuses: List<Status>): LiveData<DeletionResult> = liveData(IO) {
        emit(DeletionResult(isDeleting = true))
        val result = repository.deleteStatuses(statuses)
        emit(DeletionResult(statuses = statuses, deleted = result))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun createDeleteRequest(context: Context, statuses: List<Status>): LiveData<PendingIntent> = liveData(IO) {
        val uris = statuses.getMediaStoreUris(context)
        if (uris.isNotEmpty()) {
            emit(MediaStore.createDeleteRequest(context.contentResolver, uris))
        }
    }

    fun getUpdateState(context: Context): LiveData<UpdateState> = liveData(IO + ioHandler) {
        if (context.isDownloadingAnUpdate()) {
            emit(UpdateState(false))
        } else {
            val localUpdateUri = context.getInstallableUpdateUri()
            if (localUpdateUri != null) {
                /*
                 * We originally downloaded the APK into the public "Downloads" directory
                 * as we want to give the user the ability to easily find and install it
                 * on their own. However, to carry out the automatic installation on our part,
                 * we must copy it to our internal directory.
                 */
                val uri = context.contentResolver.openInputStream(localUpdateUri)?.use { sourceStream ->
                    val cacheFile = File(context.cacheDir, "Latest.apk")
                    if (!cacheFile.exists() || cacheFile.delete()) cacheFile.createNewFile()
                    cacheFile.outputStream().use { destStream ->
                        sourceStream.copyTo(destStream)
                    }
                    cacheFile.getUri()
                }
                emit(UpdateState(false, uri))
            } else {
                emit(UpdateState(context.isAbleToUpdate()))
            }
        }
    }

    fun getLatestRelease(): LiveData<GitHubRelease> = liveData(IO + ioHandler) {
        emit(updateService.latestRelease(DEFAULT_USER, DEFAULT_REPO))
    }

    fun downloadRelease(context: Context, release: GitHubRelease) = viewModelScope.launch(IO + ioHandler) {
        val releasePackage = release.downloads.firstOrNull { it.isApk }
        if (releasePackage != null) {
            val downloadManager = context.getSystemService<DownloadManager>()
            if (downloadManager != null) {
                context.preferences().lastUpdateId = downloadManager.enqueue(releasePackage.getDownloadRequest(context))
            }
        }
    }

    private val ioHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("WhatSave", "Exception occurred during execution of a coroutine", throwable)
    }
}

internal typealias StatusesLiveData = MutableLiveData<List<Status>>
internal typealias StatusesLiveDataMap = EnumMap<StatusType, StatusesLiveData>

internal fun newStatusesLiveDataMap() = StatusesLiveDataMap(StatusType::class.java)

internal fun StatusesLiveDataMap.getOrCreateLiveData(type: StatusType): StatusesLiveData {
    var liveData = this[type]
    if (liveData == null) {
        liveData = StatusesLiveData(arrayListOf()).also {
            this[type] = it
        }
    }
    return liveData
}