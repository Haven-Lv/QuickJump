package com.haven.quickjump

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Collections

/**
 * 快捷跳转 —— 直达快递取件码/待取页面。
 * 不联网、不申请权限、不收集不存储任何数据。
 * 自定义入口链接仅保存在本机 App 私有目录（SharedPreferences），不上传。
 *
 * 跳转模型（入口核对参考开源项目 EidenLiu/pickup-code-launcher，MIT）：
 * 每个目标由多条「跳转链」组成（目标包名 + 该包名下多条 deeplink），按顺序尝试；
 * 全部失败后依次拉起候选包名的 App 首页；最后尝试网页兜底。
 *
 * 入口链接可自定义：长按按钮编辑，保存在本地后优先于内置默认链接生效。
 * 目标 App 更新导致链接失效时，无需重新打包，长按按钮改链接即可。
 */
class MainActivity : AppCompatActivity() {

    /** 一条跳转链：目标包名 + 该包名下按优先级排列的多条 deeplink */
    private data class Chain(val pkg: String, val uris: List<String>)

    /**
     * @param chains      跳转链列表，按顺序尝试（如：正版抖音链 → 极速版链）
     * @param openAppPkgs 链全部失败后，依次尝试直接拉起的 App 包名
     * @param webUri      网页兜底入口（先试目标 App 内打开，再试浏览器）
     */
    private data class Entry(
        val title: String,
        val chains: List<Chain>,
        val openAppPkgs: List<String> = emptyList(),
        val webUri: String? = null
    )

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdge()

        // 按本地保存的顺序构建两个分组与卡片
        buildHome()

        // 处理桌面长按快捷方式带入的跳转请求
        handleDispatchIntent(intent)

        // 右上角铅笔：进入/退出排序编辑模式
        findViewById<View>(R.id.btn_edit).setOnClickListener { toggleEditMode() }

        // 左下角问号：点开「关于」（内容固定，不提供修改入口）
        findViewById<View>(R.id.btn_about).setOnClickListener { showAbout() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDispatchIntent(intent)
    }

    // ---------- 首页分组、排序与编辑模式 ----------

    /** 一个分组：标题、小图标、默认成员、每行列数 */
    private data class GroupDef(
        val key: String,
        val titleRes: Int,
        val iconRes: Int,
        val iconTintRes: Int,
        val defaultItems: List<String>,
        val columns: Int
    )

    /** 一张卡片的展示信息 */
    private data class ItemMeta(
        val titleRes: Int,
        val descRes: Int,
        val iconRes: Int,
        val tileTintRes: Int
    )

    /** 首页两个分组；linkedMapOf 保证默认顺序稳定 */
    private val groups = linkedMapOf(
        GROUP_CODE to GroupDef(
            GROUP_CODE, R.string.group_code, R.drawable.ic_section_code, R.color.group_code_tint,
            listOf(KEY_CAINIAO, KEY_TAOBAO, KEY_PDD), 3
        ),
        GROUP_PACKAGES to GroupDef(
            GROUP_PACKAGES, R.string.group_packages, R.drawable.ic_section_package, R.color.group_packages_tint,
            listOf(
                KEY_EXPRESS, KEY_PDD_PENDING, KEY_DOUYIN, KEY_JD,
                KEY_XHS, KEY_BILI, KEY_KS, KEY_DEWU, KEY_VIP
            ), 3
        )
    )

    private val itemMeta = mapOf(
        KEY_CAINIAO to ItemMeta(
            R.string.short_cainiao, R.string.desc_cainiao, R.drawable.ic_cainiao, R.color.tile_cainiao
        ),
        KEY_TAOBAO to ItemMeta(
            R.string.short_taobao, R.string.desc_taobao, R.drawable.ic_taobao, R.color.tile_taobao
        ),
        KEY_PDD to ItemMeta(
            R.string.short_pdd, R.string.desc_pdd, R.drawable.ic_pdd, R.color.tile_pdd
        ),
        KEY_EXPRESS to ItemMeta(
            R.string.short_express, R.string.desc_express, R.drawable.ic_express, R.color.tile_express
        ),
        KEY_PDD_PENDING to ItemMeta(
            R.string.short_pdd_pending, R.string.desc_pdd_pending, R.drawable.ic_pdd, R.color.tile_pdd_pending
        ),
        KEY_DOUYIN to ItemMeta(
            R.string.short_douyin, R.string.desc_douyin, R.drawable.ic_douyin, R.color.tile_douyin
        ),
        KEY_JD to ItemMeta(
            R.string.short_jd, R.string.desc_jd, R.drawable.ic_jd, R.color.tile_jd
        ),
        KEY_XHS to ItemMeta(
            R.string.short_xhs, R.string.desc_xhs, R.drawable.ic_xhs, R.color.tile_xhs
        ),
        KEY_BILI to ItemMeta(
            R.string.short_bili, R.string.desc_bili, R.drawable.ic_bili, R.color.tile_bili
        ),
        KEY_KS to ItemMeta(
            R.string.short_ks, R.string.desc_ks, R.drawable.ic_ks, R.color.tile_ks
        ),
        KEY_DEWU to ItemMeta(
            R.string.short_dewu, R.string.desc_dewu, R.drawable.ic_dewu, R.color.tile_dewu
        ),
        KEY_VIP to ItemMeta(
            R.string.short_vip, R.string.desc_vip, R.drawable.ic_vip, R.color.tile_vip
        )
    )

    private var editMode = false

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * 内容延伸到状态栏/导航栏后面：
     * 背景渐变铺满整屏（包括底部手势条区域），控件按 insets 让开系统栏；
     * 状态栏、导航栏图标颜色跟随深浅色反转。
     */
    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val root = findViewById<View>(R.id.root)
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                baseTop + bars.top,
                view.paddingRight,
                baseBottom + bars.bottom
            )
            insets
        }
    }

    /** 右上角铅笔：进入/退出排序编辑模式 */
    private fun toggleEditMode() {
        editMode = !editMode
        buildHome()
        Toast.makeText(
            this,
            if (editMode) R.string.edit_mode_on else R.string.edit_mode_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 按当前顺序重建整个首页（非编辑模式下跳过被隐藏的入口） */
    private fun buildHome() {
        val container = findViewById<LinearLayout>(R.id.group_container)
        container.removeAllViews()

        var rendered = 0
        currentGroupOrder().forEach { groupKey ->
            val all = currentItemOrder(groupKey)
            val visible = if (editMode) all else all.filter { !isHidden(it) }
            if (visible.isEmpty()) return@forEach

            if (rendered > 0) container.addView(makeDivider())
            container.addView(makeGroupView(groupKey, all, visible))
            rendered++
        }

        findViewById<ImageButton>(R.id.btn_edit).apply {
            setImageResource(if (editMode) R.drawable.ic_check else R.drawable.ic_edit)
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (editMode) R.color.accent else R.color.icon_secondary)
            )
        }
    }

    private fun makeDivider(): View = View(this).apply {
        val vertical = dp(12)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).also { it.setMargins(0, vertical, 0, vertical) }
        setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
    }

    /**
     * @param allItems     组内完整顺序（用于判断左右箭头是否还能移动）
     * @param visibleItems 当前要渲染的入口（非编辑模式会剔除被隐藏的）
     */
    private fun makeGroupView(
        groupKey: String,
        allItems: List<String>,
        visibleItems: List<String>
    ): View {
        val def = groups.getValue(groupKey)
        val view = layoutInflater.inflate(
            R.layout.section_group, findViewById<LinearLayout>(R.id.group_container), false
        )

        view.findViewById<ImageView>(R.id.section_icon).apply {
            setImageResource(def.iconRes)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.section_icon_bg)
            )
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, def.iconTintRes)
            )
        }
        view.findViewById<TextView>(R.id.section_title).setText(def.titleRes)

        view.findViewById<ImageButton>(R.id.section_swap).apply {
            visibility = if (editMode && groups.size > 1) View.VISIBLE else View.GONE
            setOnClickListener { swapGroups() }
        }

        // 按列数切分成多行，行尾用占位控件补齐，保证每张卡片等宽
        val rows = view.findViewById<LinearLayout>(R.id.items_container)
        visibleItems.chunked(def.columns).forEach { chunk ->
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            chunk.forEach { itemKey ->
                row.addView(makeItemView(groupKey, itemKey, allItems.indexOf(itemKey), allItems.size))
            }
            repeat((def.columns - chunk.size).coerceAtLeast(0)) {
                row.addView(makePlaceholder())
            }
            rows.addView(row)
        }
        return view
    }

    /** 补齐空位用，不可见但占同样宽度 */
    private fun makePlaceholder(): View {
        val gap = dp(4)
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                .also { it.setMargins(gap, gap, gap, gap) }
        }
    }

    private fun makeItemView(groupKey: String, itemKey: String, index: Int, count: Int): View {
        val meta = itemMeta.getValue(itemKey)
        val card = layoutInflater.inflate(
            R.layout.item_entry, findViewById<LinearLayout>(R.id.group_container), false
        )

        val gap = dp(4)
        card.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).also { it.setMargins(gap, gap, gap, gap) }

        card.findViewById<ImageView>(R.id.item_icon).apply {
            setImageResource(meta.iconRes)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, meta.tileTintRes)
            )
        }
        card.findViewById<TextView>(R.id.item_title).setText(meta.titleRes)
        card.findViewById<TextView>(R.id.item_desc).setText(meta.descRes)
        card.findViewById<LinearLayout>(R.id.edit_bar).visibility =
            if (editMode) View.VISIBLE else View.GONE

        // 隐藏开关：仅编辑模式可见；处于隐藏状态时高亮并让整张卡片变暗
        val hidden = isHidden(itemKey)
        card.findViewById<ImageButton>(R.id.btn_hide).apply {
            visibility = if (editMode) View.VISIBLE else View.GONE
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (hidden) R.color.accent else R.color.icon_secondary)
            )
            setOnClickListener { toggleHidden(itemKey) }
        }
        card.alpha = if (editMode && hidden) 0.4f else 1f

        card.isClickable = !editMode
        card.isLongClickable = !editMode
        card.setOnClickListener { if (!editMode) jumpFor(itemKey) }
        card.setOnLongClickListener {
            if (editMode) {
                false
            } else {
                showEditDialog(itemKey)
                true
            }
        }

        val left = card.findViewById<ImageButton>(R.id.move_left)
        val right = card.findViewById<ImageButton>(R.id.move_right)
        left.isEnabled = index > 0
        right.isEnabled = index < count - 1
        left.alpha = if (left.isEnabled) 1f else 0.25f
        right.alpha = if (right.isEnabled) 1f else 0.25f
        left.setOnClickListener { moveItem(groupKey, itemKey, -1) }
        right.setOnClickListener { moveItem(groupKey, itemKey, 1) }

        return card
    }

    /** 分组顺序：已保存的合法项 + 未出现过的新分组补在末尾 */
    private fun currentGroupOrder(): List<String> {
        val saved = prefs.getString(PREF_ORDER_GROUPS, null)
            ?.split(",")?.filter { groups.containsKey(it) } ?: emptyList()
        return saved + groups.keys.filter { it !in saved }
    }

    /** 组内顺序：同样做一次兜底，避免以后新增入口时丢失 */
    private fun currentItemOrder(groupKey: String): List<String> {
        val defaults = groups.getValue(groupKey).defaultItems
        val saved = prefs.getString(PREF_ORDER_ITEMS + groupKey, null)
            ?.split(",")?.filter { it in defaults } ?: emptyList()
        return saved + defaults.filter { it !in saved }
    }

    private fun moveItem(groupKey: String, itemKey: String, delta: Int) {
        val order = currentItemOrder(groupKey).toMutableList()
        val from = order.indexOf(itemKey)
        val to = from + delta
        if (from < 0 || to !in order.indices) return
        Collections.swap(order, from, to)
        prefs.edit().putString(PREF_ORDER_ITEMS + groupKey, order.joinToString(",")).apply()
        buildHome()
    }

    private fun swapGroups() {
        val order = currentGroupOrder().toMutableList()
        if (order.size < 2) return
        Collections.swap(order, 0, 1)
        prefs.edit().putString(PREF_ORDER_GROUPS, order.joinToString(",")).apply()
        buildHome()
        Toast.makeText(this, R.string.groups_swapped, Toast.LENGTH_SHORT).show()
    }

    // ---------- 隐藏入口（仅保存在本机） ----------

    private fun hiddenKeys(): Set<String> =
        prefs.getString(PREF_HIDDEN, null)
            ?.split(",")?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    private fun isHidden(itemKey: String): Boolean = itemKey in hiddenKeys()

    /** 编辑模式下点卡片右上角的小横线：隐藏 / 取消隐藏 */
    private fun toggleHidden(itemKey: String) {
        val keys = hiddenKeys().toMutableSet()
        val nowHidden = if (keys.contains(itemKey)) {
            keys.remove(itemKey)
            false
        } else {
            keys.add(itemKey)
            true
        }
        prefs.edit().putString(PREF_HIDDEN, keys.joinToString(",")).apply()
        buildHome()
        Toast.makeText(
            this,
            if (nowHidden) R.string.item_hidden else R.string.item_shown,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun handleDispatchIntent(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_TARGET) ?: return
        if (ENTRIES.containsKey(key)) jumpFor(key)
    }

    /** 应用用户自定义链接（若有）后执行跳转 */
    private fun jumpFor(key: String) {
        val base = ENTRIES.getValue(key)
        val custom = loadCustomUris(key)
        val entry = if (custom.isNotEmpty()) {
            // 自定义链接替换第一条链；其余内置链仍作为兜底
            base.copy(chains = listOf(base.chains.first().copy(uris = custom)) + base.chains.drop(1))
        } else {
            base
        }
        jump(entry)
    }

    /** 多级回退跳转：安装检查 → 链 → 拉起 App → 网页 */
    private fun jump(entry: Entry) {
        // 第 0 级：目标 App 一个都没装，直接提示去安装（跳到浏览器没有意义）
        val pkgs = (entry.openAppPkgs + entry.chains.map { it.pkg }).distinct()
        if (pkgs.none { isAppInstalled(it) }) {
            promptInstall(entry)
            return
        }
        // 第 1 级：逐条链、逐条 deeplink 尝试（setPackage 限定目标 App）
        for (chain in entry.chains) {
            for (uri in chain.uris) {
                if (tryOpen(uri, chain.pkg)) return
            }
        }
        // 第 2 级：直接拉起目标 App（支持多个候选包名，如正版/极速版）
        for (pkg in entry.openAppPkgs) {
            if (tryOpenApp(entry.title, pkg)) return
        }
        // 第 3 级：网页兜底——先试目标 App 内渲染，再退浏览器
        entry.webUri?.let { web ->
            for (chain in entry.chains) {
                if (tryOpen(web, chain.pkg)) return
            }
            if (tryOpen(web, null)) return
        }
        showDialog("暂时无法打开「${entry.title}」\n可长按按钮更新入口链接，或确认对应 App 已安装")
    }

    /** 目标 App 未安装：只提示，不跳浏览器也不跳商店 */
    private fun promptInstall(entry: Entry) {
        val appName = entry.title.removeSuffix("待取快递").removeSuffix("身份码")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.not_installed_title, appName))
            .setMessage(R.string.not_installed_message)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun tryOpen(uri: String, pkg: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (pkg != null) intent.setPackage(pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun tryOpenApp(title: String, pkg: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP
                or Intent.FLAG_ACTIVITY_SINGLE_TOP
                or Intent.FLAG_ACTIVITY_NEW_TASK
        )
        return try {
            startActivity(launch)
            Toast.makeText(
                this,
                "${title}没有公开直达链接，已打开官方 App，请在 App 内查看",
                Toast.LENGTH_LONG
            ).show()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun showDialog(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("无法打开")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------- 自定义入口链接（仅存本机） ----------

    /** 「关于」弹窗：展示说明文字，URL 自动可点击；附入口体检入口 */
    private fun showAbout() {
        val tv = TextView(this).apply {
            text = getString(R.string.about_text)
            textSize = 14f
            setLineSpacing(4f, 1f)
            setPadding(48, 32, 48, 16)
            movementMethod = LinkMovementMethod.getInstance()
            Linkify.addLinks(this, Linkify.WEB_URLS)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setNeutralButton("入口体检") { _, _ -> showDiagnostics() }
            .show()
    }

    /**
     * 入口体检：逐条检测每条 deeplink 在当前手机上是否有页面响应。
     * 结果可选中复制或截图发给作者，用于远程定位失效链接。
     */
    private fun showDiagnostics() {
        val sb = StringBuilder()
        sb.appendLine("设备: Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})")
        sb.appendLine("App 版本: ${BuildConfig.VERSION_NAME}")
        sb.appendLine()
        for ((_, entry) in ENTRIES) {
            sb.appendLine("【${entry.title}】")
            for (chain in entry.chains) {
                val installed = isAppInstalled(chain.pkg)
                sb.appendLine(" 目标App ${if (installed) "✓已安装" else "✗未安装"} (${chain.pkg})")
                for (uri in chain.uris) {
                    val ok = installed && canResolve(uri, chain.pkg)
                    sb.appendLine("  ${if (ok) "✓可用" else "✗无响应"} ${shortUri(uri)}")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("✗无响应 = 该链接在当前版本上没有页面处理（未安装或链接已失效）")
        sb.append("长按对应按钮可更新链接；截图发给作者可远程修复")
        val tv = TextView(this).apply {
            text = sb.toString()
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(40, 24, 40, 8)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("入口体检")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun shortUri(uri: String): String =
        if (uri.length <= 60) uri else uri.substring(0, 60) + "..."

    private fun isAppInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun canResolve(uri: String, pkg: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (pkg != null) intent.setPackage(pkg)
        packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    private fun loadCustomUris(key: String): List<String> =
        prefs.getString(key, null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 长按按钮：编辑该入口的 deeplink 列表，每行一条，按顺序尝试 */
    private fun showEditDialog(key: String) {
        val base = ENTRIES.getValue(key)
        val current = loadCustomUris(key).ifEmpty { base.chains.first().uris }
        val input = EditText(this).apply {
            setText(current.joinToString("\n"))
            setSingleLine(false)
            minLines = 4
            gravity = Gravity.TOP
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("编辑「${base.title}」入口")
            .setMessage(
                "每行一条 deeplink，按顺序尝试。\n" +
                    "目标 App 更新后链接失效时，用「开发者助手」App 查看新链接后填到这里即可。\n" +
                    "选择「恢复默认」将清除自定义内容。"
            )
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val lines = input.text.toString()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                prefs.edit().putString(key, lines.joinToString("\n")).apply()
                Toast.makeText(
                    this,
                    if (lines.isEmpty()) "已清空，将使用内置默认链接" else "已保存 ${lines.size} 条自定义链接",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                prefs.edit().remove(key).apply()
                Toast.makeText(this, "已恢复内置默认链接", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val PREFS_NAME = "entry_config"
        private const val EXTRA_TARGET = "jump_target"

        private const val KEY_CAINIAO = "cainiao"
        private const val KEY_TAOBAO = "taobao"
        private const val KEY_PDD = "pdd"
        private const val KEY_EXPRESS = "express"
        private const val KEY_PDD_PENDING = "pdd_pending"
        private const val KEY_DOUYIN = "douyin"
        private const val KEY_JD = "jd"
        private const val KEY_XHS = "xhs"
        private const val KEY_BILI = "bili"
        private const val KEY_KS = "ks"
        private const val KEY_DEWU = "dewu"
        private const val KEY_VIP = "vip"

        // 分组与排序（保存在本机）
        private const val GROUP_CODE = "code"
        private const val GROUP_PACKAGES = "packages"
        private const val PREF_ORDER_GROUPS = "order_groups"
        private const val PREF_ORDER_ITEMS = "order_items_"
        private const val PREF_HIDDEN = "hidden_items"

        // 常用包名
        private const val PKG_DOUYIN = "com.ss.android.ugc.aweme"          // 抖音
        private const val PKG_DOUYIN_LITE = "com.ss.android.ugc.aweme.lite" // 抖音极速版

        // ⚠️ 内置默认入口链接，核对日期：2026-09~10。失效时可长按按钮临时改，或更新此处在打包。
        private val ENTRIES = mapOf(

            // 1) 菜鸟身份码：菜鸟裹裹「出库码」页（实测直达 identity_code.IdentityCodeActivity）
            KEY_CAINIAO to Entry(
                title = "菜鸟身份码",
                chains = listOf(
                    Chain(
                        pkg = "com.cainiao.wireless",
                        uris = listOf("cainiao://router/station_code")
                    )
                ),
                openAppPkgs = listOf("com.cainiao.wireless"),
                webUri = "https://market.m.taobao.com/app/cn-yz/multi-activity/authCode.html?bizEntry=ALIPAY_GUOGUO"
            ),

            // 2) 淘宝身份码：tbopen 壳链接打开淘宝内 H5 身份码页
            KEY_TAOBAO to Entry(
                title = "淘宝身份码",
                chains = listOf(
                    Chain(
                        pkg = "com.taobao.taobao",
                        uris = listOf(
                            "taobao://m.taobao.com/tbopen/index.html?h5Url=https%3A%2F%2Fpages-fast.m.taobao.com%2Fwow%2Fz%2Funiapp%2F1011717%2Flast-mile-fe%2Fend-collect-platform%2Fidentity-code%3Fx-ssr%3Dtrue",
                            "tbopen://m.taobao.com/tbopen/index.html?h5Url=https%3A%2F%2Fpages-fast.m.taobao.com%2Fwow%2Fz%2Funiapp%2F1011717%2Flast-mile-fe%2Fend-collect-platform%2Fidentity-code%3Fx-ssr%3Dtrue"
                        )
                    )
                ),
                webUri = "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code?x-ssr=true"
            ),

            // 3) 淘宝待取快递：淘宝内菜鸟包裹待取列表页（校园版页面）
            KEY_EXPRESS to Entry(
                title = "淘宝待取快递",
                chains = listOf(
                    Chain(
                        pkg = "com.taobao.taobao",
                        uris = listOf(
                            "taobao://m.taobao.com/tbopen/index.html?h5Url=https%3A%2F%2Fpages-fast.m.taobao.com%2Fwow%2Fz%2Funiapp%2F1100333%2Flast-mile-fe%2Fm-end-school-tab%2Fhome",
                            "tbopen://m.taobao.com/tbopen/index.html?h5Url=https%3A%2F%2Fpages-fast.m.taobao.com%2Fwow%2Fz%2Funiapp%2F1100333%2Flast-mile-fe%2Fm-end-school-tab%2Fhome"
                        )
                    )
                ),
                webUri = "https://pages-fast.m.taobao.com/wow/z/uniapp/1100333/last-mile-fe/m-end-school-tab/home"
            ),

            // 4) 拼多多身份码：多多驿站取件码页（tab=ID_CODE），多条降级参数
            KEY_PDD to Entry(
                title = "拼多多身份码",
                chains = listOf(
                    Chain(
                        pkg = "com.xunmeng.pinduoduo",
                        uris = listOf(
                            "pinduoduo://com.xunmeng.pinduoduo/mdkd/package?tab=ID_CODE&entry_source=11&refer_page_name=login&refer_page_sn=10169",
                            "pinduoduo://com.xunmeng.pinduoduo/mdkd/package?tab=ID_CODE&entry_source=11",
                            "pinduoduo://com.xunmeng.pinduoduo/mdkd/package"
                        )
                    )
                ),
                webUri = "https://m.pinduoduo.net/mdkd/package?entry_source=18&extra_params=from_wx_jump%3D1&p_channel=0"
            ),

            // 5) 拼多多待取快递：多多驿站包裹列表页（默认 tab）
            KEY_PDD_PENDING to Entry(
                title = "拼多多待取快递",
                chains = listOf(
                    Chain(
                        pkg = "com.xunmeng.pinduoduo",
                        uris = listOf("pinduoduo://com.xunmeng.pinduoduo/mdkd/package")
                    )
                ),
                openAppPkgs = listOf("com.xunmeng.pinduoduo"),
                webUri = "https://m.pinduoduo.net/mdkd/package?entry_source=18&extra_params=from_wx_jump%3D1&p_channel=0"
            ),

            // 6) 抖音待取快递：商城订单列表（可看待收货/物流）→ 钱包（我的快递入口）
            //    兼容正版抖音（snssdk1128）与抖音极速版（snssdk2329）
            KEY_DOUYIN to Entry(
                title = "抖音待取快递",
                chains = listOf(
                    Chain(
                        pkg = PKG_DOUYIN,
                        uris = listOf(
                            "snssdk1128://ecom_order_list",
                            "snssdk1128://wallet"
                        )
                    ),
                    Chain(
                        pkg = PKG_DOUYIN_LITE,
                        uris = listOf(
                            "snssdk2329://ecom_order_list",
                            "snssdk2329://wallet"
                        )
                    )
                ),
                openAppPkgs = listOf(PKG_DOUYIN, PKG_DOUYIN_LITE)
            ),

            // 7) 京东待取快递：京东订单列表（来自上游 df94cd7「新增京东入口」）
            KEY_JD to Entry(
                title = "京东待取快递",
                chains = listOf(
                    Chain(
                        pkg = "com.jingdong.app.mall",
                        uris = listOf(
                            "openjd://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22orderlist%22%7D"
                        )
                    )
                ),
                openAppPkgs = listOf("com.jingdong.app.mall"),
                webUri = "https://order.jd.com/center/list.action"
            ),

            // 8) 小红书待取快递：订单列表（深链来自 EidenLiu/pickup-code-launcher）
            KEY_XHS to Entry(
                title = "小红书待取快递",
                chains = listOf(
                    Chain(
                        pkg = "com.xingin.xhs",
                        uris = listOf("xhsdiscover://rn/lancer-order/order/list")
                    )
                ),
                openAppPkgs = listOf("com.xingin.xhs"),
                webUri = "https://www.xiaohongshu.com/"
            ),

            // 9) 哔哩哔哩待取快递：会员购订单页
            //    先用 https 在 App 内打开商城订单页，再退到 App 首页
            KEY_BILI to Entry(
                title = "哔哩哔哩待取快递",
                chains = listOf(
                    Chain(
                        pkg = "tv.danmaku.bili",
                        uris = listOf(
                            "bilibili://mall/order/list",
                            "bilibili://"
                        )
                    )
                ),
                openAppPkgs = listOf("tv.danmaku.bili"),
                webUri = "https://mall.bilibili.com/neul/index.html?page=box_me&noTitleBar=1"
            ),

            // 10) 快手待取快递：快手小店订单
            //     兼容正版快手（kwai）与快手极速版（ksnebula）
            KEY_KS to Entry(
                title = "快手待取快递",
                chains = listOf(
                    Chain(
                        pkg = "com.smile.gifmaker",
                        uris = listOf(
                            "kwai://webview?url=https%3A%2F%2Fapp.kwaixiaodian.com%2Fpage%2Fkwaishop-trade-order-list-app%3FlayoutType%3D4%26hyId%3DkwaishopOrder",
                            "kwai://merchanthome",
                            "kwai://mywallet",
                            "kwai://"
                        )
                    ),
                    Chain(
                        pkg = "com.kuaishou.nebula",
                        uris = listOf(
                            "ksnebula://webview?url=https%3A%2F%2Fapp.kwaixiaodian.com%2Fpage%2Fkwaishop-trade-order-list-app%3FlayoutType%3D4%26hyId%3DkwaishopOrder",
                            "ksnebula://merchanthome",
                            "ksnebula://mywallet",
                            "ksnebula://"
                        )
                    )
                ),
                openAppPkgs = listOf("com.smile.gifmaker", "com.kuaishou.nebula"),
                webUri = "https://app.kwaixiaodian.com/page/kwaishop-trade-order-list-app?layoutType=4&hyId=kwaishopOrder"
            ),

            // 11) 得物待取快递：买家订单列表（默认「全部」tab）
            KEY_DEWU to Entry(
                title = "得物待取快递",
                chains = listOf(
                    Chain(
                        pkg = "com.shizhuang.duapp",
                        uris = listOf("dewuapp://m.dewu.com/router/order/buyer/orderList?tabId=0")
                    )
                ),
                openAppPkgs = listOf("com.shizhuang.duapp"),
                webUri = "https://m.dewu.com/"
            ),

            // 12) 唯品会待取快递：订单列表
            KEY_VIP to Entry(
                title = "唯品会待取快递",
                chains = listOf(
                    Chain(
                        pkg = "com.achievo.vipshop",
                        uris = listOf("vipshop://showOrderList")
                    )
                ),
                openAppPkgs = listOf("com.achievo.vipshop"),
                webUri = "https://m.vip.com/"
            )
        )
    }
}
