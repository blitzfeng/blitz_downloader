package com.blitz.downloader.activity

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blitz.downloader.R
import com.blitz.downloader.data.VideoTagRepository
import com.blitz.downloader.databinding.ActivityTagManageBinding
import com.blitz.downloader.ui.TagManageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 标签管理页，由 [ManageActivity] 通过菜单进入。
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
    private lateinit var repo: VideoTagRepository

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
            binding.toolbarTagManage.setPadding(0, statusBars.top, 0, 0)
            // RecyclerView 底部 padding 保留原有 80dp + 导航栏高度，确保最后一项不被 FAB 遮挡
            binding.rvTagManage.updatePadding(bottom = (80 * resources.displayMetrics.density).toInt() + navBars.bottom)
            // FAB 额外加上导航栏高度，防止被底部导航栏遮挡
            val fabParams = binding.fabAddTag.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            fabParams.bottomMargin = (16 * resources.displayMetrics.density).toInt() + navBars.bottom
            binding.fabAddTag.layoutParams = fabParams
            insets
        }

        repo = VideoTagRepository(this)

        setupRecyclerView()

        binding.fabAddTag.setOnClickListener { showAddTagDialog() }

        loadTags()
    }

    override fun onPause() {
        super.onPause()
        if (orderDirty) persistOrder()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ── 列表初始化 ────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = TagManageAdapter(
            onEdit = { pos, name -> showEditTagDialog(pos, name) },
            onDelete = { pos, name -> showDeleteTagDialog(pos, name) },
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

    // ── 数据加载 ──────────────────────────────────────────────────────────────

    private fun loadTags() {
        lifecycleScope.launch {
            val tags = withContext(Dispatchers.IO) { repo.getAvailableTags() }
            adapter.submitList(tags)
        }
    }

    // ── 排序持久化 ─────────────────────────────────────────────────────────────

    private fun persistOrder() {
        orderDirty = false
        val ordered = adapter.getTagList()
        lifecycleScope.launch(Dispatchers.IO) {
            repo.reorderTags(ordered)
        }
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
                    Toast.makeText(this, "标签名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val exists = withContext(Dispatchers.IO) {
                        repo.getAvailableTags().any { it.equals(name, ignoreCase = false) }
                    }
                    if (exists) {
                        Toast.makeText(this@TagManageActivity, "标签「$name」已存在", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    withContext(Dispatchers.IO) { repo.createTag(name) }
                    adapter.addItem(name)
                    binding.rvTagManage.scrollToPosition(adapter.itemCount - 1)
                    orderDirty = true
                }
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
                when {
                    newName.isBlank() -> {
                        Toast.makeText(this, "标签名不能为空", Toast.LENGTH_SHORT).show()
                    }
                    newName == oldName -> { /* 无变化，忽略 */ }
                    else -> {
                        lifecycleScope.launch {
                            val exists = withContext(Dispatchers.IO) {
                                repo.getAvailableTags().any { it == newName }
                            }
                            if (exists) {
                                Toast.makeText(this@TagManageActivity, "标签「$newName」已存在", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            withContext(Dispatchers.IO) { repo.renameTag(oldName, newName) }
                            adapter.renameAt(position, newName)
                        }
                    }
                }
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
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.deleteTag(tagName) }
                    adapter.removeAt(position)
                    orderDirty = true
                    Toast.makeText(this@TagManageActivity, "已删除「$tagName」", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
