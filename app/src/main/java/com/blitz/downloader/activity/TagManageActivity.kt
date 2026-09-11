package com.blitz.downloader.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.adapter.TagManageAdapter
import com.blitz.downloader.data.VideoTagRepository
import com.blitz.downloader.databinding.ActivityTagManageBinding
import com.blitz.downloader.dialog.TagDescriptionDialogFragment
import com.blitz.downloader.util.applyStatusBarPadding
import com.blitz.downloader.viewmodel.TagManageEvent
import com.blitz.downloader.viewmodel.TagManageViewModel
import kotlinx.coroutines.launch

/**
 * 标签管理页，由管理页（[com.blitz.downloader.fragment.ManageFragment]）通过菜单进入。
 *
 * 功能：
 * - 展示 `tags` 表中所有标签，按 sortOrder 排序
 * - 拖拽把手调整顺序（离开页面时自动持久化）
 * - 点击编辑图标重命名标签（同步到 `tags` + `video_tags`）
 * - 点击删除图标删除标签（同步到 `tags` + `video_tags`）
 * - FAB 新建标签
 */
class TagManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTagManageBinding
    private lateinit var adapter: TagManageAdapter

    private val viewModel: TagManageViewModel by viewModels()

    /** 标记当前顺序是否已被用户修改，onPause 时才需要写库。 */
    private var orderDirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        binding = ActivityTagManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarTagManage)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // status bar → Toolbar 顶部 padding；导航栏 → FAB margin 和 RecyclerView 底部 padding。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(navBars.left, 0, navBars.right, 0)
            binding.toolbarTagManage.applyStatusBarPadding(statusBars.top)
            // RecyclerView 底部 padding 保留原有 80dp + 导航栏高度，确保最后一项不被 FAB 遮挡
            binding.rvTagManage.updatePadding(bottom = (80 * resources.displayMetrics.density).toInt() + navBars.bottom)
            // FAB 额外加上导航栏高度，防止被底部导航栏遮挡
            val fabParams = binding.fabAddTag.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            fabParams.bottomMargin = (16 * resources.displayMetrics.density).toInt() + navBars.bottom
            binding.fabAddTag.layoutParams = fabParams
            insets
        }

        setupRecyclerView()

        binding.fabAddTag.setOnClickListener { showAddTagDialog() }

        supportFragmentManager.setFragmentResultListener(
            TagDescriptionDialogFragment.REQUEST_KEY,
            this,
        ) { _, bundle ->
            val tagName = bundle.getString(TagDescriptionDialogFragment.RESULT_TAG_NAME).orEmpty()
            val description = bundle.getString(TagDescriptionDialogFragment.RESULT_DESCRIPTION).orEmpty()
            viewModel.setTagDescription(tagName, description)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { handleEvent(it) }
            }
        }
        viewModel.loadTags()
    }

    override fun onPause() {
        super.onPause()
        if (!orderDirty) return
        orderDirty = false
        // 落库跑在 viewModelScope 里，不会因为页面开始销毁而被取消
        viewModel.persistOrder(adapter.getTagList())
    }

    private fun handleEvent(event: TagManageEvent) {
        when (event) {
            is TagManageEvent.TagsLoaded ->
                adapter.submitList(event.tags, event.parentMap, event.descriptionMap, event.collectFolderMap)
            is TagManageEvent.TagCreated -> {
                adapter.addItem(event.name)
                binding.rvTagManage.scrollToPosition(adapter.itemCount - 1)
                orderDirty = true
            }
            is TagManageEvent.TagRenamed -> {
                adapter.renameAt(event.position, event.newName)
                adapter.renameParentReferences(event.oldName, event.newName)
                adapter.renameDescriptionReference(event.oldName, event.newName)
                adapter.renameFolderReferences(event.oldName, event.newName)
            }
            is TagManageEvent.TagDeleted -> {
                adapter.removeAt(event.position)
                adapter.clearParentReferences(event.name)
                adapter.clearDescriptionReference(event.name)
                adapter.clearFolderReferences(event.name)
                orderDirty = true
                toast("已删除「${event.name}」")
            }
            is TagManageEvent.TagAlreadyExists -> toast("标签「${event.name}」已存在")
            is TagManageEvent.ShowParentPicker -> showParentPickerDialog(event)
            is TagManageEvent.TagParentSet ->
                adapter.updateParent(event.position, event.tagName, event.parentTagName)
            is TagManageEvent.TagDescriptionSet ->
                adapter.updateDescription(event.tagName, event.description)
            is TagManageEvent.TagFoldersSet -> {
                adapter.updateCollectFolders(event.position, event.tagName, event.collectFolderNames)
                val msg = if (event.collectFolderNames.isNotBlank()) {
                    getString(R.string.tag_manage_map_folder_saved, event.tagName)
                } else {
                    getString(R.string.tag_manage_map_folder_cleared, event.tagName)
                }
                toast(msg)
            }
            is TagManageEvent.TagsExported -> {
                if (event.count == 0) {
                    toast(getString(R.string.tag_manage_empty_export))
                    return
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Tags JSON", event.json)
                clipboard.setPrimaryClip(clip)
                toast(getString(R.string.tag_manage_copied_to_clipboard, event.count))
            }
        }
    }

    private fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_tag_manage, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_export_tags_json -> {
                if (orderDirty) {
                    orderDirty = false
                    viewModel.persistOrder(adapter.getTagList())
                }
                viewModel.exportTagsJson(adapter.getTagList())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── 列表初始化 ────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = TagManageAdapter(
            onEdit = { pos, name -> showEditTagDialog(pos, name) },
            onDelete = { pos, name -> showDeleteTagDialog(pos, name) },
            onSetParent = { pos, name -> viewModel.requestParentPicker(pos, name) },
            onEditDescription = { _, name ->
                TagDescriptionDialogFragment.show(this, name, adapter.getDescription(name))
            },
            onMapFolder = { pos, name -> showMapFolderDialog(pos, name) },
        )

        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                orderDirty = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            // 拖拽时给条目加高亮背景
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                viewHolder?.itemView?.alpha = if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) 0.8f else 1f
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.alpha = 1f
            }

            override fun isLongPressDragEnabled() = false
        }

        val itemTouchHelper = ItemTouchHelper(touchCallback)
        adapter.attachItemTouchHelper(itemTouchHelper)

        binding.rvTagManage.layoutManager = LinearLayoutManager(this)
        binding.rvTagManage.adapter = adapter
        itemTouchHelper.attachToRecyclerView(binding.rvTagManage)
    }

    // ── 对话框：新建标签 ──────────────────────────────────────────────────────

    private fun showAddTagDialog() {
        val et = buildTagEditText(this)
        AlertDialog.Builder(this)
            .setTitle("新建标签")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isBlank()) {
                    toast("标签名不能为空")
                    return@setPositiveButton
                }
                viewModel.createTag(name)
            }
            .setNegativeButton("取消", null)
            .show()
        et.requestFocus()
    }

    // ── 对话框：重命名标签 ────────────────────────────────────────────────────

    private fun showEditTagDialog(position: Int, oldName: String) {
        val et = buildTagEditText(this, prefill = oldName)
        AlertDialog.Builder(this)
            .setTitle("重命名标签")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isBlank()) {
                    toast("标签名不能为空")
                    return@setPositiveButton
                }
                // 无变化时 ViewModel 内部会直接返回
                viewModel.renameTag(position, oldName, newName)
            }
            .setNegativeButton("取消", null)
            .show()
        et.requestFocus()
        et.selectAll()
    }

    // ── 对话框：删除标签 ──────────────────────────────────────────────────────

    private fun showDeleteTagDialog(position: Int, tagName: String) {
        AlertDialog.Builder(this)
            .setTitle("删除标签")
            .setMessage("删除「$tagName」后，所有关联该标签的视频将同步解除关联，无法撤销。\n\n确定删除？")
            .setPositiveButton("删除") { _, _ -> viewModel.deleteTag(position, tagName) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 对话框：设置上级标签 ──────────────────────────────────────────────────

    /**
     * 单选选择上级标签；候选列表已由 ViewModel 排除自身与后代（防环）。
     * 第一项固定"无（顶层标签）"，选中即清除上级。这只影响标签名册的层级元数据，
     * 不会给任何视频补写标签——见 [com.blitz.downloader.data.VideoTagRepository.setParentTag] KDoc。
     */
    private fun showParentPickerDialog(event: TagManageEvent.ShowParentPicker) {
        val options = (listOf("无（顶层标签）") + event.candidates).toTypedArray()
        val checked = event.candidates.indexOf(event.currentParent) + 1 // 找不到（无上级）时是 0
        AlertDialog.Builder(this)
            .setTitle("设置「${event.tagName}」的上级标签")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val parent = if (which == 0) "" else event.candidates[which - 1]
                viewModel.setParentTag(event.position, event.tagName, parent)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 对话框：映射抖音收藏夹 ────────────────────────────────────────────────

    private fun showMapFolderDialog(position: Int, tagName: String) {
        val currentFolders = adapter.getCollectFolders(tagName)
        val formattedPrefill = VideoTagRepository.parseFolderNames(currentFolders).joinToString("\n")
        val density = resources.displayMetrics.density
        val hPad = (20 * density).toInt()
        val vPad = (12 * density).toInt()

        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 3
            maxLines = 6
            hint = getString(R.string.tag_manage_map_folder_hint)
            setPadding(hPad, vPad, hPad, vPad)
            if (formattedPrefill.isNotEmpty()) {
                setText(formattedPrefill)
                setSelection(formattedPrefill.length)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tag_manage_map_folder_title))
            .setMessage(getString(R.string.tag_manage_map_folder_dialog_msg, tagName))
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val text = et.text.toString()
                viewModel.setTagCollectFolders(position, tagName, text)
            }
            .setNegativeButton("取消", null)
            .show()
        et.requestFocus()
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    /** 构建统一样式的标签名输入框，限制最多 20 字符。 */
    private fun buildTagEditText(ctx: Context, prefill: String = ""): EditText {
        val density = ctx.resources.displayMetrics.density
        val hPad = (20 * density).toInt()
        val vPad = (12 * density).toInt()
        return EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(20))
            hint = "最多 20 个字符"
            setPadding(hPad, vPad, hPad, vPad)
            if (prefill.isNotEmpty()) {
                setText(prefill)
                setSelection(prefill.length)
            }
        }
    }
}
