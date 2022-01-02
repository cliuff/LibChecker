package com.absinthe.libchecker.viewmodel

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.absinthe.libchecker.LibCheckerApp
import com.absinthe.libchecker.SystemServices
import com.absinthe.libchecker.annotation.ACTIVITY
import com.absinthe.libchecker.annotation.ALL
import com.absinthe.libchecker.annotation.DEX
import com.absinthe.libchecker.annotation.LibType
import com.absinthe.libchecker.annotation.METADATA
import com.absinthe.libchecker.annotation.NATIVE
import com.absinthe.libchecker.annotation.NOT_MARKED
import com.absinthe.libchecker.annotation.PERMISSION
import com.absinthe.libchecker.annotation.PROVIDER
import com.absinthe.libchecker.annotation.RECEIVER
import com.absinthe.libchecker.annotation.SERVICE
import com.absinthe.libchecker.annotation.STATUS_INIT_END
import com.absinthe.libchecker.annotation.STATUS_NOT_START
import com.absinthe.libchecker.annotation.STATUS_START_INIT
import com.absinthe.libchecker.annotation.STATUS_START_REQUEST_CHANGE
import com.absinthe.libchecker.annotation.STATUS_START_REQUEST_CHANGE_END
import com.absinthe.libchecker.bean.LibChip
import com.absinthe.libchecker.bean.LibReference
import com.absinthe.libchecker.bean.LibStringItem
import com.absinthe.libchecker.bean.StatefulComponent
import com.absinthe.libchecker.constant.Constants
import com.absinthe.libchecker.constant.GlobalValues
import com.absinthe.libchecker.constant.OnceTag
import com.absinthe.libchecker.constant.librarymap.IconResMap
import com.absinthe.libchecker.database.AppItemRepository
import com.absinthe.libchecker.database.Repositories
import com.absinthe.libchecker.database.entity.LCItem
import com.absinthe.libchecker.database.entity.RuleEntity
import com.absinthe.libchecker.ui.fragment.IListController
import com.absinthe.libchecker.utils.LCAppUtils
import com.absinthe.libchecker.utils.PackageUtils
import com.absinthe.libchecker.utils.harmony.ApplicationDelegate
import com.absinthe.libchecker.utils.harmony.HarmonyOsUtil
import com.absinthe.libraries.utils.manager.TimeRecorder
import com.microsoft.appcenter.analytics.Analytics
import jonathanfinerty.once.Once
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import ohos.bundle.IBundleManager
import timber.log.Timber
import java.util.regex.Pattern

class HomeViewModel(application: Application) : AndroidViewModel(application) {

  val dbItems: LiveData<List<LCItem>> = Repositories.lcRepository.allDatabaseItems
  val libReference: MutableLiveData<List<LibReference>?> = MutableLiveData()

  private val _effect: MutableSharedFlow<Effect> = MutableSharedFlow()
  val effect = _effect.asSharedFlow()

  var controller: IListController? = null
  var libRefSystemApps: Boolean? = null
  var libRefType: Int? = null
  var appListStatus: Int = STATUS_NOT_START

  fun reloadApps() {
    setEffect {
      Effect.ReloadApps()
    }
  }

  fun packageChanged(packageName: String, action: String) {
    setEffect {
      Effect.PackageChanged(packageName, action)
    }
  }

  private fun updateInitProgress(progress: Int) {
    setEffect {
      Effect.UpdateInitProgress(progress)
    }
  }

  private fun updateAppListStatus(status: Int) {
    setEffect {
      Effect.UpdateAppListStatus(status)
    }
    appListStatus = status
  }

  private fun setEffect(builder: () -> Effect) {
    val newEffect = builder()
    viewModelScope.launch {
      _effect.emit(newEffect)
    }
  }

  private var initJob: Job? = null

  fun initItems() {
    if (initJob == null || initJob!!.isActive.not()) {
      initJob = viewModelScope.launch(Dispatchers.IO) {
        Timber.d("initItems: START")

        val context: Context = getApplication<LibCheckerApp>()
        val timeRecorder = TimeRecorder()
        timeRecorder.start()

        updateAppListStatus(STATUS_START_INIT)
        Repositories.lcRepository.deleteAllItems()
        updateInitProgress(0)

        val appList = PackageUtils.getAppsList()
        val lcItems = mutableListOf<LCItem>()
        val isHarmony = HarmonyOsUtil.isHarmonyOs()
        val bundleManager by lazy { ApplicationDelegate(context).iBundleManager }

        var packageInfo: PackageInfo
        var versionCode: Long
        var abiType: Int
        var variant: Short
        var isSystemType: Boolean

        var lcItem: LCItem
        var count = 0
        var progressCount = 0

        for (info in appList) {
          try {
            packageInfo = PackageUtils.getPackageInfo(info)
            versionCode = PackageUtils.getVersionCode(packageInfo)
            abiType = PackageUtils.getAbi(info)
            isSystemType =
              (info.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM

            variant = if (isHarmony && bundleManager?.getBundleInfo(
                info.packageName,
                IBundleManager.GET_BUNDLE_DEFAULT
              ) != null
            ) {
              Constants.VARIANT_HAP
            } else {
              Constants.VARIANT_APK
            }

            lcItem = LCItem(
              info.packageName,
              info.loadLabel(context.packageManager).toString(),
              packageInfo.versionName.orEmpty(),
              versionCode,
              packageInfo.firstInstallTime,
              packageInfo.lastUpdateTime,
              isSystemType,
              abiType.toShort(),
              PackageUtils.isSplitsApk(packageInfo),
              null/* delay init */,
              packageInfo.applicationInfo.targetSdkVersion.toShort(),
              variant
            )

            lcItems.add(lcItem)
            count++
            progressCount++
            updateInitProgress(progressCount * 100 / appList.size)
          } catch (e: Throwable) {
            Timber.e(e, "initItems")
            continue
          }

          if (count == 50) {
            insert(lcItems)
            lcItems.clear()
            count = 0
          }
        }

        insert(lcItems)
        lcItems.clear()
        updateAppListStatus(STATUS_INIT_END)

        timeRecorder.end()
        Timber.d("initItems: END, $timeRecorder")
        updateAppListStatus(STATUS_NOT_START)
        initJob = null
      }.also {
        it.start()
      }
    }
  }

  fun requestChange(needRefresh: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
    if (appListStatus == STATUS_START_REQUEST_CHANGE || appListStatus == STATUS_START_INIT) {
      Timber.d("Request change appListStatusLiveData not equals STATUS_START")
      return@launch
    }

    requestChangeImpl(SystemServices.packageManager, needRefresh)
  }

  private suspend fun requestChangeImpl(
    packageManager: PackageManager,
    needRefresh: Boolean = false
  ) {
    Timber.d("Request change: START")
    val timeRecorder = TimeRecorder()
    var appList: MutableList<ApplicationInfo>? =
      AppItemRepository.getApplicationInfoItems().toMutableList()

    timeRecorder.start()
    updateAppListStatus(STATUS_START_REQUEST_CHANGE)

    if (appList.isNullOrEmpty() || needRefresh) {
      appList = PackageUtils.getAppsList().toMutableList()
    }

    dbItems.value?.let { value ->
      val isHarmony = HarmonyOsUtil.isHarmonyOs()
      val bundleManager by lazy { ApplicationDelegate(LibCheckerApp.app).iBundleManager }
      var packageInfo: PackageInfo
      var versionCode: Long
      var lcItem: LCItem
      var abi: Int
      var variant: Short

      for (dbItem in value) {
        try {
          appList.find { it.packageName == dbItem.packageName }?.let {
            packageInfo = PackageUtils.getPackageInfo(it)
            versionCode = PackageUtils.getVersionCode(packageInfo)

            if (packageInfo.lastUpdateTime != dbItem.lastUpdatedTime ||
              (dbItem.lastUpdatedTime == 0L && versionCode != dbItem.versionCode)
            ) {
              abi = PackageUtils.getAbi(it)

              variant = if (isHarmony && bundleManager?.getBundleInfo(
                  it.packageName,
                  IBundleManager.GET_BUNDLE_DEFAULT
                ) != null
              ) {
                Constants.VARIANT_HAP
              } else {
                Constants.VARIANT_APK
              }

              lcItem = LCItem(
                it.packageName,
                it.loadLabel(packageManager).toString(),
                packageInfo.versionName ?: "null",
                versionCode,
                packageInfo.firstInstallTime,
                packageInfo.lastUpdateTime,
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM,
                abi.toShort(),
                PackageUtils.isSplitsApk(packageInfo),
                PackageUtils.isKotlinUsed(packageInfo),
                packageInfo.applicationInfo.targetSdkVersion.toShort(),
                variant
              )
              update(lcItem)
            }

            appList.remove(it)
          } ?: run {
            delete(dbItem)
          }
        } catch (e: Exception) {
          Timber.e(e)
          continue
        }
      }

      for (info in appList) {
        try {
          packageInfo = PackageUtils.getPackageInfo(info)
          versionCode = PackageUtils.getVersionCode(packageInfo)

          variant = if (isHarmony && bundleManager?.getBundleInfo(
              info.packageName,
              IBundleManager.GET_BUNDLE_DEFAULT
            ) != null
          ) {
            Constants.VARIANT_HAP
          } else {
            Constants.VARIANT_APK
          }

          lcItem = LCItem(
            info.packageName,
            info.loadLabel(packageManager).toString(),
            packageInfo.versionName ?: "null",
            versionCode,
            packageInfo.firstInstallTime,
            packageInfo.lastUpdateTime,
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM,
            PackageUtils.getAbi(info).toShort(),
            PackageUtils.isSplitsApk(packageInfo),
            PackageUtils.isKotlinUsed(packageInfo),
            packageInfo.applicationInfo.targetSdkVersion.toShort(),
            variant
          )

          insert(lcItem)
        } catch (e: Exception) {
          Timber.e(e)
          continue
        }
      }
      GlobalValues.shouldRequestChange.postValue(false)
      AppItemRepository.shouldRefreshAppList = true
    } ?: run {
      GlobalValues.shouldRequestChange.postValue(true)
    }

    updateAppListStatus(STATUS_START_REQUEST_CHANGE_END)
    timeRecorder.end()
    Timber.d("Request change: END, $timeRecorder")
    updateAppListStatus(STATUS_NOT_START)

    if (!Once.beenDone(Once.THIS_APP_VERSION, OnceTag.HAS_COLLECT_LIB)) {
      delay(10000)
      collectPopularLibraries(appList.toList())
      Once.markDone(OnceTag.HAS_COLLECT_LIB)
    }
  }

  private fun collectPopularLibraries(appList: List<ApplicationInfo>) =
    viewModelScope.launch(Dispatchers.IO) {
      if (GlobalValues.isAnonymousAnalyticsEnabled.value == false) {
        return@launch
      }
      val map = HashMap<String, Int>()
      var libList: List<LibStringItem>
      var count: Int

      try {
        for (item in appList) {
          libList =
            PackageUtils.getNativeDirLibs(PackageUtils.getPackageInfo(item.packageName))

          for (lib in libList) {
            count = map[lib.name] ?: 0
            map[lib.name] = count + 1
          }
        }
        val properties: MutableMap<String, String> = HashMap()

        for (entry in map) {
          if (entry.value > 3 && LCAppUtils.getRuleWithRegex(entry.key, NATIVE) == null) {
            properties.clear()
            properties["Library name"] = entry.key
            properties["Library count"] = entry.value.toString()

            Analytics.trackEvent("Native Library", properties)
          }
        }

        collectComponentPopularLibraries(
          appList,
          SERVICE,
          "Service"
        )
        collectComponentPopularLibraries(
          appList,
          ACTIVITY,
          "Activity"
        )
        collectComponentPopularLibraries(
          appList,
          RECEIVER,
          "Receiver"
        )
        collectComponentPopularLibraries(
          appList,
          PROVIDER,
          "Provider"
        )
      } catch (ignore: Exception) {
        Timber.e(ignore, "collectPopularLibraries failed")
      }
    }

  private suspend fun collectComponentPopularLibraries(
    appList: List<ApplicationInfo>,
    @LibType type: Int,
    label: String
  ) {
    val map = HashMap<String, Int>()
    var compLibList: List<StatefulComponent>
    var count: Int

    for (item in appList) {
      try {
        compLibList = PackageUtils.getComponentList(item.packageName, type, false)

        for (lib in compLibList) {
          count = map[lib.componentName] ?: 0
          map[lib.componentName] = count + 1
        }
      } catch (e: Exception) {
        Timber.e(e)
        continue
      }
    }

    val properties: MutableMap<String, String> = HashMap()

    for (entry in map) {
      if (entry.value > 3 && LCAppUtils.getRuleWithRegex(entry.key, type) == null) {
        properties.clear()
        properties["Library name"] = entry.key
        properties["Library count"] = entry.value.toString()

        Analytics.trackEvent("$label Library", properties)
      }
    }
  }

  private var computeLibReferenceJob: Job? = null

  fun computeLibReference(@LibType type: Int) {
    computeLibReferenceJob = viewModelScope.launch(Dispatchers.IO) {
      libReference.postValue(null)
      val appList: List<ApplicationInfo> =
        AppItemRepository.getApplicationInfoItems().ifEmpty { PackageUtils.getAppsList() }
      val map = HashMap<String, Pair<MutableList<String>, Int>>()
      val refList = mutableListOf<LibReference>()
      val showSystem = GlobalValues.isShowSystemApps.value ?: false

      var libList: List<LibStringItem>
      var packageInfo: PackageInfo
      var onlyShowNotMarked = false

      when (type) {
        ALL, NOT_MARKED -> {
          if (type == NOT_MARKED) {
            onlyShowNotMarked = true
          }
          for (item in appList) {

            if (!showSystem && ((item.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM)) {
              continue
            }

            computeComponentReference(map, item.packageName, NATIVE)
            computeComponentReference(map, item.packageName, SERVICE)
            computeComponentReference(map, item.packageName, ACTIVITY)
            computeComponentReference(map, item.packageName, RECEIVER)
            computeComponentReference(map, item.packageName, PROVIDER)
            computeComponentReference(map, item.packageName, PERMISSION)
            computeComponentReference(map, item.packageName, METADATA)
          }
        }
        NATIVE -> {
          for (item in appList) {

            if (!showSystem && ((item.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM)) {
              continue
            }

            computeComponentReference(map, item.packageName, NATIVE)
          }
        }
        SERVICE -> {
          for (item in appList) {

            if (!showSystem && ((item.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM)) {
              continue
            }

            computeComponentReference(map, item.packageName, SERVICE)
          }
        }
        ACTIVITY -> {
          for (item in appList) {

            if (!showSystem && ((item.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM)) {
              continue
            }

            computeComponentReference(map, item.packageName, ACTIVITY)
          }
        }
        RECEIVER -> {
          for (item in appList) {

            if (!showSystem && ((item.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM)) {
              continue
            }

            computeComponentReference(map, item.packageName, RECEIVER)
          }
        }
        PROVIDER -> {
          for (item in appList) {

            if (!showSystem && ((item.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM)) {
              continue
            }

            computeComponentReference(map, item.packageName, PROVIDER)
          }
        }
        DEX -> {
          for (item in appList) {

            if (!showSystem && ((item.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM)) {
              continue
            }

            computeComponentReference(map, item.packageName, DEX)
          }
        }
        PERMISSION -> {
          for (item in appList) {

            if (!showSystem && ((item.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM)) {
              continue
            }

            computeComponentReference(map, item.packageName, PERMISSION)
          }
        }
        METADATA -> {
          for (item in appList) {

            if (!showSystem && ((item.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM)) {
              continue
            }

            computeComponentReference(map, item.packageName, METADATA)
          }
        }
      }

      var chip: LibChip?
      var rule: RuleEntity?
      for (entry in map) {
        if (entry.value.first.size >= GlobalValues.libReferenceThreshold && entry.key.isNotBlank()) {
          rule = LCAppUtils.getRuleWithRegex(entry.key, entry.value.second)
          chip = null
          rule?.let {
            chip = LibChip(
              iconRes = IconResMap.getIconRes(it.iconIndex),
              name = it.label,
              regexName = it.regexName
            )
          }
          if (!onlyShowNotMarked) {
            refList.add(
              LibReference(
                entry.key,
                chip,
                entry.value.first,
                entry.value.second
              )
            )
          } else {
            if (rule == null) {
              refList.add(
                LibReference(
                  entry.key,
                  chip,
                  entry.value.first,
                  entry.value.second
                )
              )
            }
          }
        }
      }

      refList.sortByDescending { it.referredList.size }
      libReference.postValue(refList)
    }
  }

  fun cancelComputingLibReference() {
    computeLibReferenceJob?.cancel()
    computeLibReferenceJob = null
  }

  private fun computeComponentReference(
    referenceMap: HashMap<String, Pair<MutableList<String>, Int>>,
    packageName: String,
    @LibType type: Int
  ) {
    try {
      when (type) {
        NATIVE -> {
          val packageInfo = PackageUtils.getPackageInfo(packageName)
          computeNativeReferenceInternal(
            referenceMap,
            packageName,
            PackageUtils.getNativeDirLibs(packageInfo)
          )
        }
        SERVICE -> {
          val packageInfo = PackageUtils.getPackageInfo(
            packageName,
            PackageManager.GET_SERVICES
          )
          computeComponentReferenceInternal(referenceMap, packageName, type, packageInfo.services)
        }
        ACTIVITY -> {
          val packageInfo = PackageUtils.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES
          )
          computeComponentReferenceInternal(referenceMap, packageName, type, packageInfo.activities)
        }
        RECEIVER -> {
          val packageInfo = PackageUtils.getPackageInfo(
            packageName,
            PackageManager.GET_RECEIVERS
          )
          computeComponentReferenceInternal(referenceMap, packageName, type, packageInfo.receivers)
        }
        PROVIDER -> {
          val packageInfo = PackageUtils.getPackageInfo(
            packageName,
            PackageManager.GET_PROVIDERS
          )
          computeComponentReferenceInternal(referenceMap, packageName, type, packageInfo.providers)
        }
        DEX -> {
          computeDexReferenceInternal(
            referenceMap,
            packageName,
            PackageUtils.getDexList(packageName)
          )
        }
        PERMISSION -> {
          val packageInfo = PackageUtils.getPackageInfo(
            packageName,
            PackageManager.GET_PERMISSIONS
          )
          computePermissionReferenceInternal(
            referenceMap,
            packageName,
            packageInfo.requestedPermissions
          )
        }
        METADATA -> {
          val packageInfo = PackageUtils.getPackageInfo(
            packageName,
            PackageManager.GET_META_DATA
          )
          computeMetadataReferenceInternal(
            referenceMap,
            packageName,
            packageInfo.applicationInfo.metaData
          )
        }
      }
    } catch (e: Exception) {
      Timber.e(e)
    }
  }

  private fun computeNativeReferenceInternal(
    referenceMap: HashMap<String, Pair<MutableList<String>, Int>>,
    packageName: String,
    list: List<LibStringItem>
  ) {
    list.forEach {
      if (referenceMap[it.name] == null) {
        referenceMap[it.name] = mutableListOf<String>() to NATIVE
      }
      referenceMap[it.name]!!.first.add(packageName)
    }
  }

  private fun computeComponentReferenceInternal(
    referenceMap: HashMap<String, Pair<MutableList<String>, Int>>,
    packageName: String,
    @LibType type: Int,
    components: Array<out ComponentInfo>
  ) {
    components.forEach {
      if (referenceMap[it.name] == null) {
        referenceMap[it.name] = mutableListOf<String>() to type
      }
      referenceMap[it.name]!!.first.add(packageName)
    }
  }

  private fun computeDexReferenceInternal(
    referenceMap: HashMap<String, Pair<MutableList<String>, Int>>,
    packageName: String,
    list: List<LibStringItem>
  ) {
    list.forEach {
      if (referenceMap[it.name] == null) {
        referenceMap[it.name] = mutableListOf<String>() to DEX
      }
      referenceMap[it.name]!!.first.add(packageName)
    }
  }

  private fun computePermissionReferenceInternal(
    referenceMap: HashMap<String, Pair<MutableList<String>, Int>>,
    packageName: String,
    list: Array<out String>
  ) {
    list.forEach {
      if (referenceMap[it] == null) {
        referenceMap[it] = mutableListOf<String>() to PERMISSION
      }
      referenceMap[it]!!.first.add(packageName)
    }
  }

  private fun computeMetadataReferenceInternal(
    referenceMap: HashMap<String, Pair<MutableList<String>, Int>>,
    packageName: String,
    bundle: Bundle
  ) {
    bundle.keySet().forEach {
      if (referenceMap[it] == null) {
        referenceMap[it] = mutableListOf<String>() to METADATA
      }
      referenceMap[it]!!.first.add(packageName)
    }
  }

  fun refreshRef() {
    libReference.value?.let { ref ->
      libReference.value =
        ref.filter { it.referredList.size >= GlobalValues.libReferenceThreshold }
    }
  }

  private suspend fun insert(item: LCItem) = Repositories.lcRepository.insert(item)

  private suspend fun insert(list: List<LCItem>) = Repositories.lcRepository.insert(list)

  private suspend fun update(item: LCItem) = Repositories.lcRepository.update(item)

  private suspend fun delete(item: LCItem) = Repositories.lcRepository.delete(item)

  fun initRegexRules() = viewModelScope.launch(Dispatchers.IO) {
    val list = Repositories.ruleRepository.getRegexRules()
    list.forEach {
      AppItemRepository.rulesRegexList[Pattern.compile(it.name)] = it
    }
  }

  sealed class Effect {
    data class ReloadApps(val obj: Any? = null) : Effect()
    data class UpdateInitProgress(val progress: Int) : Effect()
    data class UpdateAppListStatus(val status: Int) : Effect()
    data class PackageChanged(val packageName: String, val action: String) : Effect()
  }
}
