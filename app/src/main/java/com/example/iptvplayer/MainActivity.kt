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
import com.example.iptvplayer.data.PlaybackSession
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: PlaylistRepository
    private lateinit var adapter: ChannelAdapter

    private var subscriptions: List<Subscription> = emptyList()
    private var activeSubscription: Subscription? = null
    private var allChannels: List<Channel> = emptyList()
    /** Pre-bucketed by group for O(1) group switches. */
    private var channelsByGroup: Map<String, List<Channel>> = emptyMap()
    private var groupItems: List<GroupItem> = emptyList()
    private var currentGroup: String = GROUP_ALL
    private var query: String = ""

    private var filterJob: Job? = null
    private var reloadJob: Job? = null
    /** Bumps on each filter request so stale results are dropped. */
    private var filterGeneration = 0

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
        binding.recycler.setHasFixedSize(true)
        binding.recycler.setItemViewCacheSize(20)
        binding.recycler.adapter = adapter

        binding.fabImport.setOnClickListener { showImportDialog() }
        binding.btnEmptyImport.setOnClickListener { showImportDialog() }
        binding.subscriptionPickerRow.setOnClickListener { showSubscriptionPicker() }
        binding.groupPickerRow.setOnClickListener { showGroupPicker() }

        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            scheduleFilter(debounceMs = if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MS)
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

    /**
     * Load subscription meta + active channels off the main thread.
     * Switching sources and cold start used to parse the entire multi-subscription
     * JSON on the UI thread — catastrophic at tens of thousands of channels.
     */
    private fun reload(showProgress: Boolean = allChannels.isEmpty()) {
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch {
            if (showProgress) setLoading(true)
            val snapshot = withContext(Dispatchers.IO) {
                val subs = repo.loadSubscriptions()
                val activeMeta = repo.getActiveSubscription()
                val channels = if (activeMeta != null) {
                    repo.loadChannels(activeMeta.id)
                } else {
                    emptyList()
                }
                val groups = buildGroupIndex(channels)
                ReloadSnapshot(
                    subscriptions = subs,
                    active = activeMeta,
                    channels = channels,
                    byGroup = groups.first,
                    groupItems = groups.second
                )
            }
            subscriptions = snapshot.subscriptions
            activeSubscription = snapshot.active
            allChannels = snapshot.channels
            channelsByGroup = snapshot.byGroup
            groupItems = snapshot.groupItems
            if (currentGroup != GROUP_ALL && currentGroup !in channelsByGroup) {
                currentGroup = GROUP_ALL
            }
            updateSubscriptionChrome()
            updateGroupChrome()
            updateEmptyState()
            if (showProgress) setLoading(false)
            applyFilterImmediate()
        }
    }

    private data class ReloadSnapshot(
        val subscriptions: List<Subscription>,
        val active: Subscription?,
        val channels: List<Channel>,
        val byGroup: Map<String, List<Channel>>,
        val groupItems: List<GroupItem>
    )

    private fun buildGroupIndex(
        channels: List<Channel>
    ): Pair<Map<String, List<Channel>>, List<GroupItem>> {
        if (channels.isEmpty()) {
            return emptyMap<String, List<Channel>>() to listOf(GroupItem(GROUP_ALL, 0))
        }
        val map = LinkedHashMap<String, MutableList<Channel>>()
        for (c in channels) {
            map.getOrPut(c.group) { ArrayList() }.add(c)
        }
        val byGroup: Map<String, List<Channel>> = map
        val sorted = byGroup.entries
            .sortedBy { it.key }
            .map { GroupItem(it.key, it.value.size) }
        val items = listOf(GroupItem(GROUP_ALL, channels.size)) + sorted
        return byGroup to items
    }

    private fun updateSubscriptionChrome() {
        val sub = activeSubscription
        binding.tvCurrentSubscription.text = sub?.name ?: getString(R.string.subscription_none)
        binding.tvSubscriptionCount.text = getString(
            R.string.subscription_count_label,
            subscriptions.size
        )
    }

    private fun updateGroupChrome() {
        binding.tvCurrentGroup.text = currentGroup
        val classCount = (groupItems.size - 1).coerceAtLeast(0)
        binding.tvGroupCount.text = getString(R.string.group_count_label, classCount)
    }

    private fun showSubscriptionPicker() {
        // Metadata only — never loads channel bodies for every subscription.
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
                        reload(showProgress = true)
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
                            reload(showProgress = true)
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
            applyFilterImmediate()
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

    private fun scheduleFilter(debounceMs: Long) {
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            applyFilterImmediate()
        }
    }

    private fun applyFilterImmediate() {
        val gen = ++filterGeneration
        val q = query.trim()
        val group = currentGroup
        val base: List<Channel> = if (group == GROUP_ALL) {
            allChannels
        } else {
            channelsByGroup[group].orEmpty()
        }
        val total = allChannels.size

        if (q.isEmpty()) {
            // Fast path: no search — reuse pre-bucketed list, no copy needed
            if (gen != filterGeneration) return
            adapter.submitList(base)
            binding.tvCount.text = getString(R.string.channel_count, base.size, total)
            return
        }

        filterJob = lifecycleScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                val needle = q.lowercase()
                val matchUrl = needle.startsWith("http") || needle.contains("://")
                base.filter { c ->
                    c.name.lowercase().contains(needle) ||
                        c.group.lowercase().contains(needle) ||
                        (matchUrl && c.url.lowercase().contains(needle))
                }
            }
            if (gen != filterGeneration) return@launch
            adapter.submitList(filtered)
            binding.tvCount.text = getString(R.string.channel_count, filtered.size, total)
        }
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
                reload(showProgress = false)
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
                reload(showProgress = false)
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
                reload(showProgress = false)
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
                reload(showProgress = false)
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
                reload(showProgress = false)
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
                reload(showProgress = false)
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
                reload(showProgress = true)
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.clear_all) { _, _ ->
                repo.clearAll()
                currentGroup = GROUP_ALL
                query = ""
                binding.search.setText("")
                reload(showProgress = true)
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPlayer(channel: Channel) {
        val filtered = adapter.currentList
        val idx = filtered.indexOfFirst { it.url == channel.url }.coerceAtLeast(0)
        // Hold queue in-process — never pack 10k+ strings into Intent extras
        PlaybackSession.set(filtered, idx)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_GROUP, channel.group)
            putExtra(PlayerActivity.EXTRA_INDEX, idx)
            putExtra(PlayerActivity.EXTRA_USE_SESSION, true)
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
        private const val SEARCH_DEBOUNCE_MS = 200L
    }
}
