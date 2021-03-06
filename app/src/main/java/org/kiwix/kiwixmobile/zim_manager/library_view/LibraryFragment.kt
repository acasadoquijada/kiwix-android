/*
 * Kiwix Android
 * Copyright (c) 2019 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.kiwix.kiwixmobile.zim_manager.library_view

import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.mhutti1.utils.storage.StorageDevice
import eu.mhutti1.utils.storage.StorageSelectDialog
import kotlinx.android.synthetic.main.activity_library.libraryErrorText
import kotlinx.android.synthetic.main.activity_library.libraryList
import kotlinx.android.synthetic.main.activity_library.librarySwipeRefresh
import org.kiwix.kiwixmobile.R
import org.kiwix.kiwixmobile.core.base.BaseActivity
import org.kiwix.kiwixmobile.core.base.BaseFragment
import org.kiwix.kiwixmobile.core.downloader.Downloader
import org.kiwix.kiwixmobile.core.entity.LibraryNetworkEntity.Book
import org.kiwix.kiwixmobile.core.extensions.ActivityExtensions.viewModel
import org.kiwix.kiwixmobile.core.extensions.snack
import org.kiwix.kiwixmobile.core.utils.BookUtils
import org.kiwix.kiwixmobile.core.utils.DialogShower
import org.kiwix.kiwixmobile.core.utils.KiwixDialog.YesNoDialog.StopDownload
import org.kiwix.kiwixmobile.core.utils.KiwixDialog.YesNoDialog.WifiOnly
import org.kiwix.kiwixmobile.core.utils.NetworkUtils
import org.kiwix.kiwixmobile.core.utils.SharedPreferenceUtil
import org.kiwix.kiwixmobile.zim_manager.NetworkState
import org.kiwix.kiwixmobile.zim_manager.NetworkState.CONNECTED
import org.kiwix.kiwixmobile.zim_manager.NetworkState.NOT_CONNECTED
import org.kiwix.kiwixmobile.zim_manager.ZimManageActivity
import org.kiwix.kiwixmobile.zim_manager.ZimManageViewModel
import org.kiwix.kiwixmobile.zim_manager.library_view.adapter.LibraryAdapter
import org.kiwix.kiwixmobile.zim_manager.library_view.adapter.LibraryDelegate.BookDelegate
import org.kiwix.kiwixmobile.zim_manager.library_view.adapter.LibraryDelegate.DividerDelegate
import org.kiwix.kiwixmobile.zim_manager.library_view.adapter.LibraryDelegate.DownloadDelegate
import org.kiwix.kiwixmobile.zim_manager.library_view.adapter.LibraryListItem
import org.kiwix.kiwixmobile.zim_manager.library_view.adapter.LibraryListItem.BookItem
import javax.inject.Inject

class LibraryFragment : BaseFragment() {

  @Inject lateinit var conMan: ConnectivityManager
  @Inject lateinit var downloader: Downloader
  @Inject lateinit var sharedPreferenceUtil: SharedPreferenceUtil
  @Inject lateinit var dialogShower: DialogShower
  @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
  @Inject lateinit var bookUtils: BookUtils
  @Inject lateinit var availableSpaceCalculator: AvailableSpaceCalculator

  private val zimManageViewModel by lazy {
    activity!!.viewModel<ZimManageViewModel>(viewModelFactory)
  }

  private val libraryAdapter: LibraryAdapter by lazy {
    LibraryAdapter(
      BookDelegate(bookUtils, ::onBookItemClick),
      DownloadDelegate {
        dialogShower.show(StopDownload, { downloader.cancelDownload(it.downloadId) })
      },
      DividerDelegate
    )
  }

  private val noWifiWithWifiOnlyPreferenceSet
    get() = sharedPreferenceUtil.prefWifiOnly && !NetworkUtils.isWiFi(context!!)

  private val isNotConnected get() = conMan.activeNetworkInfo?.isConnected == false

  override fun inject(baseActivity: BaseActivity) {
    (baseActivity as ZimManageActivity).cachedComponent.inject(this)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View = inflater.inflate(R.layout.activity_library, container, false)

  override fun onViewCreated(
    view: View,
    savedInstanceState: Bundle?
  ) {
    super.onViewCreated(view, savedInstanceState)
    librarySwipeRefresh.setOnRefreshListener(::refreshFragment)
    libraryList.run {
      adapter = libraryAdapter
      layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
      setHasFixedSize(true)
    }
    zimManageViewModel.libraryItems.observe(viewLifecycleOwner, Observer(::onLibraryItemsChange))
    zimManageViewModel.libraryListIsRefreshing.observe(
      viewLifecycleOwner, Observer(::onRefreshStateChange)
    )
    zimManageViewModel.networkStates.observe(viewLifecycleOwner, Observer(::onNetworkStateChange))
  }

  private fun onRefreshStateChange(isRefreshing: Boolean?) {
    librarySwipeRefresh.isRefreshing = isRefreshing!!
  }

  private fun onNetworkStateChange(networkState: NetworkState?) {
    when (networkState) {
      CONNECTED -> {
      }
      NOT_CONNECTED -> {
        if (libraryAdapter.itemCount > 0) {
          noInternetSnackbar()
        } else {
          libraryErrorText.setText(R.string.no_network_connection)
          libraryErrorText.visibility = VISIBLE
        }
        librarySwipeRefresh.isRefreshing = false
      }
    }
  }

  private fun noInternetSnackbar() {
    view?.snack(
      R.string.no_network_connection,
      R.string.menu_settings,
      ::openNetworkSettings
    )
  }

  private fun openNetworkSettings() {
    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
  }

  private fun onLibraryItemsChange(it: List<LibraryListItem>?) {
    libraryAdapter.items = it!!
    if (it.isEmpty()) {
      libraryErrorText.setText(
        if (isNotConnected) R.string.no_network_connection
        else R.string.no_items_msg
      )
      libraryErrorText.visibility = VISIBLE
    } else {
      libraryErrorText.visibility = GONE
    }
  }

  private fun refreshFragment() {
    if (isNotConnected) {
      noInternetSnackbar()
    } else {
      zimManageViewModel.requestDownloadLibrary.onNext(Unit)
    }
  }

  private fun downloadFile(book: Book) {
    downloader.download(book)
  }

  private fun storeDeviceInPreferences(storageDevice: StorageDevice) {
    sharedPreferenceUtil.putPrefStorage(storageDevice.name)
    sharedPreferenceUtil.putPrefStorageTitle(
      getString(
        if (storageDevice.isInternal) R.string.internal_storage
        else R.string.external_storage
      )
    )
  }

  private fun onBookItemClick(item: BookItem) {
    when {
      isNotConnected -> {
        noInternetSnackbar()
        return
      }
      noWifiWithWifiOnlyPreferenceSet -> {
        dialogShower.show(WifiOnly, {
          sharedPreferenceUtil.putPrefWifiOnly(false)
          downloadFile(item.book)
        })
        return
      }
      else -> availableSpaceCalculator.hasAvailableSpaceFor(item,
        { downloadFile(item.book) },
        {
          libraryList.snack(
            getString(R.string.download_no_space) +
              "\n" + getString(R.string.space_available) + " " +
              it,
            R.string.download_change_storage,
            ::showStorageSelectDialog
          )
        })
    }
  }

  private fun showStorageSelectDialog() = StorageSelectDialog()
    .apply {
      onSelectAction = ::storeDeviceInPreferences
    }
    .show(fragmentManager!!, getString(R.string.pref_storage))
}
