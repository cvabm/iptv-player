package com.example.iptvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.iptvplayer.data.Channel
import com.example.iptvplayer.data.PlaylistRepository
import com.example.iptvplayer.data.SourceType
import com.example.iptvplayer.data.Subscription
import com.example.iptvplayer.databinding.ActivityMainBinding
import com.example.iptvplayer.databinding.DialogImportBinding
import com.example.iptvplayer.ui.ChannelAdapter
import com.example.iptvplayer.ui.GroupAdapter
import com.example.iptvplayer.ui.GroupItem
import com.example.iptvplayer.ui.SubscriptionAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: PlaylistRepository
    private lateinit var adapter: ChannelAdapter

    private var subscriptions: List<Subscription> = emptyList()
    private var activeSubscription: Subscription? = null
    private var allChannels: List<Channel> = emptyList()
    private var groupItems: List<GroupItem> = emptyList()
    private var currentGroup: String = GROUP_ALL
    private var query: String = ""

    private val pickM3u = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // some providers don't support persistable permission
        }
        importFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        setSupportActionBar(binding.toolbar)

        repo = PlaylistRepository(this)
        adapter = ChannelAdapter { openPlayer(it) }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabImport.setOnClickListener { showImportDialog() }
        binding.btnEmptyImport.setOnClickListener { showImportDialog() }
        binding.subscriptionPickerRow.setOnClickListener { showSubscriptionPicker() }
        binding.groupPickerRow.setOnClickListener { showGroupPicker() }

        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            applyFilter()
        }

        binding.swipeRefresh.setOnRefreshListener {
            refreshActiveIfUrl(fromSwipe = true)
        }

        reload()
    }

    private fun applySystemBarInsets() {
        val fabMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.statusBarSpacer.updateLayoutParams {
                height = bars.top
            }
            binding.fabImport.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = fabMargin + bars.bottom
                marginEnd = fabMargin + bars.right
            }
            binding.recycler.updatePadding(
                bottom = fabMargin * 4 + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_import -> {
                showImportDialog()
                true
            }
            R.id.action_sources -> {
                showSubscriptionPicker()
                true
            }
            R.id.action_refresh -> {
                refreshActiveIfUrl(fromSwipe = false)
                true
            }
            R.id.action_clear -> {
                confirmClear()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun reload() {
        subscriptions = repo.loadSubscriptions()
        activeSubscription = repo.getActiveSubscription()
        allChannels = activeSubscription?.channels.orEmpty()
        rebuildGroups()
        applyFilter()
        updateSubscriptionChrome()
        updateEmptyState()
    }

    private fun updateSubscriptionChrome() {
        val sub = activeSubscription
        binding.tvCurrentSubscription.text = sub?.name ?: getString(R.string.subscription_none)
        binding.tvSubscriptionCount.text = getString(
            R.string.subscription_count_label,
            subscriptions.size
        )
    }

    private fun rebuildGroups() {
        val counts = linkedMapOf<String, Int>()
        allChannels.forEach { c ->
            counts[c.group] = (counts[c.group] ?: 0) + 1
        }
        val sorted = counts.entries
            .sortedBy { it.key }
            .map { GroupItem(it.key, it.value) }

        groupItems = listOf(GroupItem(GROUP_ALL, allChannels.size)) + sorted

        if (currentGroup != GROUP_ALL && currentGroup !in counts) {
            currentGroup = GROUP_ALL
        }
        updateGroupChrome()
    }

    private fun updateGroupChrome() {
        binding.tvCurrentGroup.text = currentGroup
        val classCount = (groupItems.size - 1).coerceAtLeast(0)
        binding.tvGroupCount.text = getString(R.string.group_count_label, classCount)
    }

    private fun showSubscriptionPicker() {
        val list = repo.loadSubscriptions()
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.no_sources, Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_subscription_picker, null, false)
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSubscriptions)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subscription_picker_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun bindList() {
            val subs = repo.loadSubscriptions()
            if (subs.isEmpty()) {
                dialog.dismiss()
                return
            }
            val adapter = SubscriptionAdapter(
                selectedId = repo.getActiveSubscriptionId(),
                onSelect = { sub ->
                    if (repo.setActiveSubscription(sub.id)) {
                        currentGroup = GROUP_ALL
                        query = ""
                        binding.search.setText("")
                        reload()
                        Toast.makeText(this, R.string.subscription_switched, Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                },
                onDelete = { sub ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.delete_subscription_title)
                        .setMessage(getString(R.string.delete_subscription_message, sub.name))
                        .setPositiveButton(R.string.delete_subscription) { _, _ ->
                            repo.deleteSubscription(sub.id)
                            currentGroup = GROUP_ALL
                            reload()
                            Toast.makeText(this, R.string.subscription_deleted, Toast.LENGTH_SHORT).show()
                            bindList()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            )
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = adapter
            adapter.submit(subs)
        }

        bindList()
        dialog.show()
    }

    private fun showGroupPicker() {
        if (groupItems.isEmpty() || allChannels.isEmpty()) {
            Toast.makeText(this, R.string.no_sources, Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_group_picker, null, false)
        val etSearch = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etGroupSearch)
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvGroups)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.group_picker_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        val groupAdapter = GroupAdapter(currentGroup) { item ->
            currentGroup = item.name
            updateGroupChrome()
            applyFilter()
            dialog.dismiss()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = groupAdapter
        groupAdapter.submit(groupItems)

        etSearch.doAfterTextChanged { editable ->
            val q = editable?.toString()?.trim().orEmpty().lowercase()
            val filtered = if (q.isEmpty()) {
                groupItems
            } else {
                groupItems.filter { it.name.lowercase().contains(q) }
            }
            groupAdapter.submit(filtered)
        }

        dialog.show()
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val filtered = allChannels.filter { c ->
            val groupOk = currentGroup == GROUP_ALL || c.group == currentGroup
            val queryOk = q.isEmpty() ||
                c.name.lowercase().contains(q) ||
                c.group.lowercase().contains(q) ||
                c.url.lowercase().contains(q)
            groupOk && queryOk
        }
        adapter.submitList(filtered)
        binding.tvCount.text = getString(R.string.channel_count, filtered.size, allChannels.size)
    }

    private fun updateEmptyState() {
        // Keep content (incl. subscription switcher) visible whenever any subscription exists
        val noSubscriptions = subscriptions.isEmpty()
        binding.emptyLayout.visibility = if (noSubscriptions) View.VISIBLE else View.GONE
        binding.contentLayout.visibility = if (noSubscriptions) View.GONE else View.VISIBLE
    }

    private fun showImportDialog() {
        val dialogBinding = DialogImportBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialogBinding.btnImportUrl.setOnClickListener {
            val url = dialogBinding.etUrl.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                dialogBinding.etUrl.error = getString(R.string.error_empty_url)
                return@setOnClickListener
            }
            dialog.dismiss()
            importUrl(url)
        }

        dialogBinding.btnPickFile.setOnClickListener {
            dialog.dismiss()
            pickM3u.launch(arrayOf("audio/*", "video/*", "text/*", "application/*", "*/*"))
        }

        dialogBinding.btnImportStream.setOnClickListener {
            val name = dialogBinding.etStreamName.text?.toString()?.trim().orEmpty()
            val url = dialogBinding.etStreamUrl.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                dialogBinding.etStreamUrl.error = getString(R.string.error_empty_url)
                return@setOnClickListener
            }
            dialog.dismiss()
            importStream(name.ifBlank { "直播流" }, url)
        }

        dialogBinding.btnImportPaste.setOnClickListener {
            val content = dialogBinding.etPaste.text?.toString().orEmpty()
            if (content.isBlank()) {
                dialogBinding.etPaste.error = getString(R.string.error_empty_paste)
                return@setOnClickListener
            }
            dialog.dismiss()
            importPaste(content)
        }

        dialog.show()
    }

    private fun importUrl(url: String) {
        setLoading(true)
        lifecycleScope.launch {
            val result = repo.importFromUrl(url)
            setLoading(false)
            result.onSuccess { sub ->
                currentGroup = GROUP_ALL
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.import_success, sub.channelCount),
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }.onFailure { e ->
                showError(e.message ?: "导入失败")
            }
        }
    }

    private fun importFile(uri: Uri) {
        setLoading(true)
        lifecycleScope.launch {
            val result = repo.importFromUri(uri)
            setLoading(false)
            result.onSuccess { sub ->
                currentGroup = GROUP_ALL
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.import_success, sub.channelCount),
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }.onFailure { e ->
                showError(e.message ?: "导入失败")
            }
        }
    }

    private fun importStream(name: String, url: String) {
        setLoading(true)
        lifecycleScope.launch {
            val result = repo.importSingleStream(name, url)
            setLoading(false)
            result.onSuccess {
                currentGroup = GROUP_ALL
                Toast.makeText(this@MainActivity, R.string.stream_added, Toast.LENGTH_SHORT).show()
                reload()
            }.onFailure { e ->
                showError(e.message ?: "添加失败")
            }
        }
    }

    private fun importPaste(content: String) {
        setLoading(true)
        lifecycleScope.launch {
            val result = repo.importRawContent(content)
            setLoading(false)
            result.onSuccess { sub ->
                currentGroup = GROUP_ALL
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.import_success, sub.channelCount),
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }.onFailure { e ->
                showError(e.message ?: "导入失败")
            }
        }
    }

    private fun refreshActiveIfUrl(fromSwipe: Boolean) {
        val sub = repo.getActiveSubscription()
        if (sub == null) {
            if (fromSwipe) binding.swipeRefresh.isRefreshing = false
            Toast.makeText(this, R.string.no_sources, Toast.LENGTH_SHORT).show()
            return
        }
        if (sub.type != SourceType.URL) {
            if (fromSwipe) {
                binding.swipeRefresh.isRefreshing = false
                reload()
                Toast.makeText(this, R.string.refresh_not_url, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.refresh_not_url, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!fromSwipe) setLoading(true)
        lifecycleScope.launch {
            val result = repo.refreshSubscription(sub.id)
            if (fromSwipe) {
                binding.swipeRefresh.isRefreshing = false
            } else {
                setLoading(false)
            }
            result.onSuccess { updated ->
                currentGroup = GROUP_ALL
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.refresh_success, updated.channelCount),
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }.onFailure { e ->
                showError(e.message ?: "刷新失败")
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_message)
            .setPositiveButton(R.string.clear_current) { _, _ ->
                repo.clearActiveSubscription()
                currentGroup = GROUP_ALL
                query = ""
                binding.search.setText("")
                reload()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.clear_all) { _, _ ->
                repo.clearAll()
                currentGroup = GROUP_ALL
                query = ""
                binding.search.setText("")
                reload()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPlayer(channel: Channel) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_GROUP, channel.group)
            val filtered = adapter.currentList
            val urls = ArrayList(filtered.map { it.url })
            val names = ArrayList(filtered.map { it.name })
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_URLS, urls)
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_NAMES, names)
            putExtra(PlayerActivity.EXTRA_INDEX, filtered.indexOfFirst { it.url == channel.url })
        }
        startActivity(intent)
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.fabImport.isEnabled = !loading
    }

    private fun showError(msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    companion object {
        private const val GROUP_ALL = "全部"
    }
}
