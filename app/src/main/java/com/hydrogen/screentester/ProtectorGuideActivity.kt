package com.hydrogen.screentester

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hydrogen.screentester.ui.theme.ScreenTesterTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class GuideColorPreset {
    DARK_CYAN,
    DARK_WHITE,
    WHITE_DUST,
    FOLLOW_SETTINGS
}

private enum class GuideStrokePreset(val px: Float?) {
    FOLLOW(null),
    PX_2(2f),
    PX_5(5f),
    PX_10(10f)
}

/**
 * 贴膜辅助对齐页：
 * 1. 准备态：提供纯黑荧光对边 / 纯白强光照尘等预设、物理圆角边缘线、十字对称中心线与四角定位标。
 * 2. 固定校准态：全屏保持常亮、锁死屏幕旋转、隔离空白区域触摸与侧滑返回，可选联动 Android 系统屏幕固定；
 *    仅可通过「轻点唤醒 -> 单指按住滑块右滑到底并保持 1 秒」两步式操作释放屏幕，防止酒精湿巾或刮板误触发。
 */
class ProtectorGuideActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        applyTestWindowSettings()

        setContent {
            ScreenTesterTheme {
                ProtectorGuideScreen(
                    onExit = { finish() },
                    onSyncSystemBars = { hideSystemBars() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun ProtectorGuideScreen(
    onExit: () -> Unit,
    onSyncSystemBars: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = view.context as Activity
    val density = LocalDensity.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var isLocked by remember { mutableStateOf(false) }
    var colorPreset by remember {
        val saved = prefs.getString("protector_guide_color_preset", GuideColorPreset.DARK_CYAN.name)
        val initial = GuideColorPreset.entries.firstOrNull { it.name == saved } ?: GuideColorPreset.DARK_CYAN
        mutableStateOf(initial)
    }
    var strokePreset by remember {
        val saved = prefs.getString("protector_guide_stroke_preset", GuideStrokePreset.PX_5.name)
        val initial = GuideStrokePreset.entries.firstOrNull { it.name == saved } ?: GuideStrokePreset.PX_5
        mutableStateOf(initial)
    }
    var showCrosshair by remember {
        mutableStateOf(prefs.getBoolean("protector_guide_crosshair", true))
    }
    var showCornerMarks by remember {
        mutableStateOf(prefs.getBoolean("protector_guide_corner_marks", true))
    }
    var boostBrightness by remember {
        mutableStateOf(prefs.getBoolean("protector_guide_boost_brightness", true))
    }
    var enableSystemPinning by remember {
        mutableStateOf(prefs.getBoolean("protector_guide_system_pinning", false))
    }

    // 锁定时侧滑返回不退出，触发一次短暂操作提示
    var backBlockedTrigger by remember { mutableIntStateOf(0) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    BackHandler(enabled = true) {
        if (isLocked) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            backBlockedTrigger++
        } else {
            onExit()
        }
    }

    // 窗口亮度同步：贴膜除尘/对边开启满亮度，关闭则回退测试页默认策略
    LaunchedEffect(boostBrightness) {
        val lp = activity.window.attributes
        lp.screenBrightness = if (boostBrightness) {
            1.0f
        } else if (ThemeSettings.isMaxBrightnessEnabled) {
            ThemeSettings.testBrightnessValue
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        activity.window.attributes = lp
    }

    // 锁定状态下的系统级防护：强制常亮 + 方向锁死 + 侧边系统手势排除 + 可选屏幕固定
    val unlockedOrientation =
        if (context.resources.configuration.smallestScreenWidthDp >= DeviceUtils.LARGE_SCREEN_MIN_WIDTH_DP) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    DisposableEffect(isLocked, rootSize, unlockedOrientation) {
        val window = activity.window
        if (isLocked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            onSyncSystemBars()

            // Android 允许单侧最多 200dp 高的系统手势排除区，降低擦拭屏幕左右中段时误触返回箭头的概率
            if (rootSize.width > 0 && rootSize.height > 0) {
                val exclHeightPx = with(density) { 200.dp.roundToPx() }.coerceAtMost(rootSize.height)
                val exclWidthPx = with(density) { 40.dp.roundToPx() }.coerceAtMost(rootSize.width / 3)
                val top = (rootSize.height - exclHeightPx) / 2
                val bottom = top + exclHeightPx
                view.systemGestureExclusionRects = listOf(
                    Rect(0, top, exclWidthPx, bottom),
                    Rect(rootSize.width - exclWidthPx, top, rootSize.width, bottom)
                )
            }
        } else {
            if (!ThemeSettings.isKeepScreenOnEnabled) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            activity.requestedOrientation = unlockedOrientation
            view.systemGestureExclusionRects = emptyList()
        }

        onDispose {
            if (!ThemeSettings.isKeepScreenOnEnabled) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            activity.requestedOrientation = unlockedOrientation
            view.systemGestureExclusionRects = emptyList()
        }
    }

    SideEffect {
        onSyncSystemBars()
    }

    val targetBgColor = when (colorPreset) {
        GuideColorPreset.DARK_CYAN -> Color.Black
        GuideColorPreset.DARK_WHITE -> Color.Black
        GuideColorPreset.WHITE_DUST -> Color.White
        GuideColorPreset.FOLLOW_SETTINGS -> Color(ThemeSettings.testLineBgColor)
    }
    val targetLineColor = when (colorPreset) {
        GuideColorPreset.DARK_CYAN -> Color(0xFF00E5FF)
        GuideColorPreset.DARK_WHITE -> Color.White
        GuideColorPreset.WHITE_DUST -> Color(0xFFE53935)
        GuideColorPreset.FOLLOW_SETTINGS -> Color(ThemeSettings.testLineColor)
    }
    val isLightCanvas = colorPreset == GuideColorPreset.WHITE_DUST ||
            (colorPreset == GuideColorPreset.FOLLOW_SETTINGS && isColorLight(ThemeSettings.testLineBgColor))

    val bgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(260),
        label = "guideBgColor"
    )
    val lineColor by animateColorAsState(
        targetValue = targetLineColor,
        animationSpec = tween(260),
        label = "guideLineColor"
    )
    val effectiveStrokePx = strokePreset.px ?: ThemeSettings.testLineThickness

    fun lockScreen() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        isLocked = true
        if (enableSystemPinning) {
            try {
                activity.startLockTask()
            } catch (_: Exception) {
                // 部分定制系统未开启屏幕固定时安全降级为应用内隔离
            }
        }
    }

    fun releaseScreen() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null && am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
                activity.stopLockTask()
            }
        } catch (_: Exception) {
        }
        isLocked = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .background(bgColor)
    ) {
        // 1. 底层：物理圆角对齐框 + 十字对称线 + 四角辅助标
        ProtectorAlignmentCanvas(
            lineColor = lineColor,
            strokePx = effectiveStrokePx,
            showCrosshair = showCrosshair,
            showCornerMarks = showCornerMarks
        )

        // 2. 锁定态：全屏空白区触摸吞噬层（除中央两步释放控件外，全屏其他位置一律不可触碰）
        if (isLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }

        // 3. 中央区域：准备态配置面板 <-> 锁定态两步防擦拭释放条
        AnimatedVisibility(
            visible = !isLocked,
            enter = fadeIn(tween(240)) + scaleIn(initialScale = 0.94f, animationSpec = tween(240, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.94f, animationSpec = tween(180, easing = FastOutSlowInEasing)),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            SetupControlCard(
                colorPreset = colorPreset,
                onColorPresetChange = {
                    colorPreset = it
                    prefs.edit { putString("protector_guide_color_preset", it.name) }
                },
                strokePreset = strokePreset,
                onStrokePresetChange = {
                    strokePreset = it
                    prefs.edit { putString("protector_guide_stroke_preset", it.name) }
                },
                showCrosshair = showCrosshair,
                onShowCrosshairChange = {
                    showCrosshair = it
                    prefs.edit { putBoolean("protector_guide_crosshair", it) }
                },
                showCornerMarks = showCornerMarks,
                onShowCornerMarksChange = {
                    showCornerMarks = it
                    prefs.edit { putBoolean("protector_guide_corner_marks", it) }
                },
                boostBrightness = boostBrightness,
                onBoostBrightnessChange = {
                    boostBrightness = it
                    prefs.edit { putBoolean("protector_guide_boost_brightness", it) }
                },
                enableSystemPinning = enableSystemPinning,
                onEnableSystemPinningChange = {
                    enableSystemPinning = it
                    prefs.edit { putBoolean("protector_guide_system_pinning", it) }
                },
                onOpenCalibration = {
                    context.startActivity(Intent(context, CornerCalibrationActivity::class.java))
                },
                onStartLock = ::lockScreen,
                onClose = onExit
            )
        }

        AnimatedVisibility(
            visible = isLocked,
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            TwoStepSlideReleaseBar(
                isLightCanvas = isLightCanvas,
                accentColor = lineColor,
                backBlockedTrigger = backBlockedTrigger,
                onRelease = ::releaseScreen
            )
        }
    }
}

@Composable
private fun ProtectorAlignmentCanvas(
    lineColor: Color,
    strokePx: Float,
    showCrosshair: Boolean,
    showCornerMarks: Boolean
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumeVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeVersion++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // 读取 resumeVersion 确保从校准车间返回后立即刷新 G2 等偏好设置
        val refreshTick = resumeVersion
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || refreshTick < 0) return@Canvas

        // 1. 屏幕物理圆角校准外框（复用全局圆角路径，兼容系统圆角、手动校准与 G2 曲线）
        val borderPath = buildScreenBorderPath(
            widthPx = w,
            heightPx = h,
            insets = view.rootWindowInsets,
            thickness = strokePx,
            context = context
        ).asComposePath()

        drawPath(
            path = borderPath,
            color = lineColor,
            style = Stroke(width = strokePx)
        )

        // 2. 十字对称中心线与四边中点刻度（中间留空，避免干扰中央释放条视线）
        if (showCrosshair) {
            val cx = w / 2f
            val cy = h / 2f
            val centerGapW = with(density) { 170.dp.toPx() }.coerceAtMost(w * 0.42f)
            val centerGapH = with(density) { 72.dp.toPx() }.coerceAtMost(h * 0.2f)
            val dashEffect = PathEffect.dashPathEffect(
                floatArrayOf(with(density) { 8.dp.toPx() }, with(density) { 6.dp.toPx() }),
                0f
            )
            val guideAlpha = 0.45f
            val thinPx = with(density) { 1.dp.toPx() }.coerceAtLeast(1f)

            // 纵向对称虚线（对准顶部挖孔/听筒与底部中心）
            if (cy - centerGapH > 0f) {
                drawLine(
                    color = lineColor.copy(alpha = guideAlpha),
                    start = Offset(cx, 0f),
                    end = Offset(cx, cy - centerGapH),
                    strokeWidth = thinPx,
                    pathEffect = dashEffect
                )
                drawLine(
                    color = lineColor.copy(alpha = guideAlpha),
                    start = Offset(cx, cy + centerGapH),
                    end = Offset(cx, h),
                    strokeWidth = thinPx,
                    pathEffect = dashEffect
                )
            }

            // 横向对称虚线（对准左右黑边余量）
            if (cx - centerGapW > 0f) {
                drawLine(
                    color = lineColor.copy(alpha = guideAlpha),
                    start = Offset(0f, cy),
                    end = Offset(cx - centerGapW, cy),
                    strokeWidth = thinPx,
                    pathEffect = dashEffect
                )
                drawLine(
                    color = lineColor.copy(alpha = guideAlpha),
                    start = Offset(cx + centerGapW, cy),
                    end = Offset(w, cy),
                    strokeWidth = thinPx,
                    pathEffect = dashEffect
                )
            }

            // 四边中点高对比短刻度
            val tickLen = with(density) { 18.dp.toPx() }
            val tickWidth = with(density) { 2.dp.toPx() }
            drawLine(lineColor, Offset(cx, 0f), Offset(cx, tickLen), strokeWidth = tickWidth)
            drawLine(lineColor, Offset(cx, h - tickLen), Offset(cx, h), strokeWidth = tickWidth)
            drawLine(lineColor, Offset(0f, cy), Offset(tickLen, cy), strokeWidth = tickWidth)
            drawLine(lineColor, Offset(w - tickLen, cy), Offset(w, cy), strokeWidth = tickWidth)
        }

        // 3. 四角内侧对位辅助直角标（方便垂直俯视观察四角间隙是否均匀）
        if (showCornerMarks) {
            val inset = with(density) { 28.dp.toPx() }
            val arm = with(density) { 22.dp.toPx() }
            val markStroke = with(density) { 1.8.dp.toPx() }
            val c = lineColor.copy(alpha = 0.65f)

            // 左上
            drawLine(c, Offset(inset, inset), Offset(inset + arm, inset), strokeWidth = markStroke, cap = StrokeCap.Round)
            drawLine(c, Offset(inset, inset), Offset(inset, inset + arm), strokeWidth = markStroke, cap = StrokeCap.Round)
            // 右上
            drawLine(c, Offset(w - inset, inset), Offset(w - inset - arm, inset), strokeWidth = markStroke, cap = StrokeCap.Round)
            drawLine(c, Offset(w - inset, inset), Offset(w - inset, inset + arm), strokeWidth = markStroke, cap = StrokeCap.Round)
            // 左下
            drawLine(c, Offset(inset, h - inset), Offset(inset + arm, h - inset), strokeWidth = markStroke, cap = StrokeCap.Round)
            drawLine(c, Offset(inset, h - inset), Offset(inset, h - inset - arm), strokeWidth = markStroke, cap = StrokeCap.Round)
            // 右下
            drawLine(c, Offset(w - inset, h - inset), Offset(w - inset - arm, h - inset), strokeWidth = markStroke, cap = StrokeCap.Round)
            drawLine(c, Offset(w - inset, h - inset), Offset(w - inset, h - inset - arm), strokeWidth = markStroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun SetupControlCard(
    colorPreset: GuideColorPreset,
    onColorPresetChange: (GuideColorPreset) -> Unit,
    strokePreset: GuideStrokePreset,
    onStrokePresetChange: (GuideStrokePreset) -> Unit,
    showCrosshair: Boolean,
    onShowCrosshairChange: (Boolean) -> Unit,
    showCornerMarks: Boolean,
    onShowCornerMarksChange: (Boolean) -> Unit,
    boostBrightness: Boolean,
    onBoostBrightnessChange: (Boolean) -> Unit,
    enableSystemPinning: Boolean,
    onEnableSystemPinningChange: (Boolean) -> Unit,
    onOpenCalibration: () -> Unit,
    onStartLock: () -> Unit,
    onClose: () -> Unit
) {
    val view = LocalView.current

    Surface(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .fillMaxWidth(),
        shape = G2Shapes.card,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(G2Shapes.icon)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CropFree,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "贴膜辅助对齐",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onOpenCalibration()
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = if (ThemeSettings.useCustomRadius) "当前：手动校准圆角（点击微调）"
                            else "当前：系统物理圆角（点击微调）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onClose()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "退出",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 底色预设选择
            Text(
                text = "场景底色（白底照灰尘 · 黑底对边缘）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            SegmentedSlideSelector(
                items = listOf(
                    GuideColorPreset.DARK_CYAN to "黑底青线",
                    GuideColorPreset.DARK_WHITE to "黑底白线",
                    GuideColorPreset.WHITE_DUST to "纯白照尘",
                    GuideColorPreset.FOLLOW_SETTINGS to "跟随设置"
                ),
                selected = colorPreset,
                onSelect = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onColorPresetChange(it)
                }
            )

            Spacer(Modifier.height(12.dp))

            // 线宽选择
            Text(
                text = "边缘线宽（加粗便于斜视悬空落膜）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            SegmentedSlideSelector(
                items = listOf(
                    GuideStrokePreset.FOLLOW to "校准线宽",
                    GuideStrokePreset.PX_2 to "2px",
                    GuideStrokePreset.PX_5 to "5px",
                    GuideStrokePreset.PX_10 to "10px"
                ),
                selected = strokePreset,
                onSelect = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onStrokePresetChange(it)
                }
            )

            Spacer(Modifier.height(14.dp))

            // 开关列表
            CompactToggleRow(
                title = "十字对称中心线",
                subtitle = "辅助对准顶部挖孔/听筒与左右边距",
                checked = showCrosshair,
                onCheckedChange = onShowCrosshairChange
            )
            CompactToggleRow(
                title = "四角内侧定位标",
                subtitle = "俯视对比四角边缘留白是否对称",
                checked = showCornerMarks,
                onCheckedChange = onShowCornerMarksChange
            )
            CompactToggleRow(
                title = "强制最高屏幕亮度",
                subtitle = "看清屏幕细微灰尘与钢化膜黑边透光",
                checked = boostBrightness,
                onCheckedChange = onBoostBrightnessChange
            )
            CompactToggleRow(
                title = "联动系统屏幕固定",
                subtitle = "深度屏蔽上滑回桌面与下拉通知栏",
                checked = enableSystemPinning,
                onCheckedChange = onEnableSystemPinningChange
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onStartLock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = G2Shapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "固定校准 · 开始贴膜",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "固定后屏幕常亮且屏蔽其他区域触控\n贴完后轻点锁标并右滑保持 1 秒即可释放",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CompactToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onCheckedChange(!checked)
            }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onCheckedChange(it)
            }
        )
    }
}

/**
 * 两步式防擦拭释放组件：
 * 第一步（休眠微标）：仅响应干净的「单指短按轻点」（多指湿布、长压、擦拭划过均不唤醒），视觉低干扰。
 * 第二步（滑动并保持 1 秒）：从左侧滑块单指右滑到底并保持 1.0 秒才释放；
 *        过程中只要出现第 2 触点（酒精棉/手掌）或纵向偏出轨道，立即中断回弹；5 秒无操作自动收回。
 */
@Composable
private fun TwoStepSlideReleaseBar(
    isLightCanvas: Boolean,
    accentColor: Color,
    backBlockedTrigger: Int,
    onRelease: () -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var isAwake by remember { mutableStateOf(false) }
    var isInteracting by remember { mutableStateOf(false) }
    var showBackHint by remember { mutableStateOf(false) }

    // 拦截侧滑返回时，短暂高亮提示用户如何释放
    LaunchedEffect(backBlockedTrigger) {
        if (backBlockedTrigger > 0) {
            showBackHint = true
            delay(2200)
            showBackHint = false
        }
    }

    // 唤醒后若 5 秒无交互，自动退回低干扰休眠微标态
    LaunchedEffect(isAwake, isInteracting) {
        if (isAwake && !isInteracting) {
            delay(5000)
            isAwake = false
        }
    }

    val containerBg = if (isLightCanvas) {
        Color.Black.copy(alpha = if (isAwake) 0.82f else if (showBackHint) 0.65f else 0.36f)
    } else {
        Color(0xFF1E1E1E).copy(alpha = if (isAwake) 0.90f else if (showBackHint) 0.72f else 0.42f)
    }
    val contentFg = Color.White

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedContent(
            targetState = isAwake,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(200))) togetherWith
                        (fadeOut(tween(160)) + scaleOut(targetScale = 0.92f, animationSpec = tween(160)))
            },
            label = "twoStepUnlockState"
        ) { awake ->
            if (!awake) {
                // 第一步：极简休眠微标 —— 仅干净单指轻点可唤醒
                val maxTapSlopPx = with(density) { 18.dp.toPx() }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(containerBg)
                        .border(
                            width = 1.dp,
                            color = contentFg.copy(alpha = if (showBackHint) 0.45f else 0.16f),
                            shape = CircleShape
                        )
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downTime = System.currentTimeMillis()
                                val startPos = down.position
                                var validSingleTap = true

                                while (true) {
                                    val event = awaitPointerEvent()
                                    // 湿布/手掌产生多点触控 → 立即判无效
                                    if (event.changes.count { it.pressed } > 1) {
                                        validSingleTap = false
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        validSingleTap = false
                                        break
                                    }
                                    change.consume()
                                    val dist = hypot(
                                        (change.position.x - startPos.x).toDouble(),
                                        (change.position.y - startPos.y).toDouble()
                                    ).toFloat()
                                    // 刮板/酒精棉划过（位移超限）→ 判无效
                                    if (dist > maxTapSlopPx) {
                                        validSingleTap = false
                                    }
                                    if (!change.pressed) {
                                        val duration = System.currentTimeMillis() - downTime
                                        // 仅 25ms..550ms 的干净单指点按才唤醒（排除长压湿布）
                                        if (validSingleTap && duration in 25..550) {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            isAwake = true
                                        }
                                        break
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = contentFg.copy(alpha = if (showBackHint) 0.95f else 0.72f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (showBackHint) "屏幕已锁定 · 轻点此处唤醒释放条"
                            else "已固定校准 · 轻点释放",
                            color = contentFg.copy(alpha = if (showBackHint) 0.95f else 0.72f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                // 第二步：按住左侧滑块右滑到底并保持 1.0 秒释放屏幕
                val trackWidthDp = 292.dp
                val trackHeightDp = 56.dp
                val padDp = 4.dp
                val thumbSizeDp = trackHeightDp - padDp * 2

                val trackWidthPx = with(density) { trackWidthDp.toPx() }
                val trackHeightPx = with(density) { trackHeightDp.toPx() }
                val padPx = with(density) { padDp.toPx() }
                val thumbSizePx = with(density) { thumbSizeDp.toPx() }
                val maxOffsetPx = (trackWidthPx - padPx * 2 - thumbSizePx).coerceAtLeast(1f)
                val verticalCorridorPx = with(density) { 48.dp.toPx() }

                val thumbOffsetAnim = remember { Animatable(0f) }
                var isHoldingAtEnd by remember { mutableStateOf(false) }
                var holdProgress by remember { mutableFloatStateOf(0f) }
                var hasReleased by remember { mutableStateOf(false) }

                // 滑到底后保持 1.0 秒倒计时，带阶段触感反馈
                LaunchedEffect(isHoldingAtEnd) {
                    if (isHoldingAtEnd && !hasReleased) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val holdDurationNs = 1_000_000_000L // 1.0s
                        val startNs = withFrameNanos { it }
                        var tick1 = false
                        var tick2 = false
                        while (true) {
                            val nowNs = withFrameNanos { it }
                            val p = ((nowNs - startNs).toFloat() / holdDurationNs).coerceIn(0f, 1f)
                            holdProgress = p
                            if (p >= 0.34f && !tick1) {
                                tick1 = true
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            if (p >= 0.68f && !tick2) {
                                tick2 = true
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            if (p >= 1f) {
                                hasReleased = true
                                onRelease()
                                break
                            }
                        }
                    } else if (!hasReleased) {
                        holdProgress = 0f
                    }
                }

                val dragFraction = (thumbOffsetAnim.value / maxOffsetPx).coerceIn(0f, 1f)
                val activeThumbColor = if (accentColor == Color.Black) Color(0xFF00E5FF) else accentColor

                Box(
                    modifier = Modifier
                        .size(width = trackWidthDp, height = trackHeightDp)
                        .clip(CircleShape)
                        .background(containerBg)
                        .border(1.dp, contentFg.copy(alpha = 0.22f), CircleShape)
                        .pointerInput(maxOffsetPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                if (hasReleased) return@awaitEachGesture
                                // 必须从左侧滑块区域内起手按下
                                if (down.position.x > padPx + thumbSizePx * 1.25f) {
                                    return@awaitEachGesture
                                }
                                isInteracting = true
                                val grabDeltaX = down.position.x - padPx - thumbOffsetAnim.value

                                var aborted = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (hasReleased) break
                                    // 一旦出现多指/湿布大面积触控，立即中断回弹
                                    if (event.changes.count { it.pressed } > 1) {
                                        aborted = true
                                        break
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()

                                    if (!change.pressed) {
                                        break
                                    }

                                    // 纵向偏出滑轨走廊（例如刮板竖向刮过）→ 立即中断回弹
                                    if (change.position.y < -verticalCorridorPx ||
                                        change.position.y > trackHeightPx + verticalCorridorPx
                                    ) {
                                        aborted = true
                                        break
                                    }

                                    val rawX = (change.position.x - padPx - grabDeltaX).coerceIn(0f, maxOffsetPx)
                                    val reachedEnd = rawX >= maxOffsetPx * 0.90f
                                    val snappedX = if (reachedEnd) maxOffsetPx else rawX
                                    isHoldingAtEnd = reachedEnd
                                    scope.launch {
                                        thumbOffsetAnim.snapTo(snappedX)
                                    }
                                }

                                isInteracting = false
                                if (!hasReleased) {
                                    // 松手或异常中断但尚未完成 1 秒释放 → 重置并平滑弹回起点
                                    isHoldingAtEnd = false
                                    if (aborted) {
                                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                    }
                                    scope.launch {
                                        thumbOffsetAnim.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f)
                                        )
                                    }
                                }
                            }
                        }
                ) {
                    // 轨道进度高亮填充
                    Box(
                        modifier = Modifier
                            .height(trackHeightDp)
                            .width(with(density) { (padPx * 2 + thumbSizePx + thumbOffsetAnim.value).toDp() })
                            .clip(CircleShape)
                            .background(
                                activeThumbColor.copy(
                                    alpha = if (isHoldingAtEnd) 0.28f + 0.25f * holdProgress else 0.18f * dragFraction
                                )
                            )
                    )

                    // 轨道中心提示文字
                    val hintAlpha by animateFloatAsState(
                        targetValue = if (isHoldingAtEnd) 1f else (1f - dragFraction * 0.6f),
                        animationSpec = tween(120),
                        label = "slideHintAlpha"
                    )
                    Text(
                        text = if (isHoldingAtEnd) {
                            val remain = ((1f - holdProgress) * 1.0f).coerceAtLeast(0f)
                            "继续按住 ${String.format("%.1f", remain)}s 释放..."
                        } else {
                            "右滑到底并按住 1 秒释放"
                        },
                        color = contentFg.copy(alpha = 0.88f * hintAlpha),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(start = 36.dp, end = 12.dp)
                    )

                    // 可拖动圆形滑块 + 1 秒保持环形进度圈
                    Box(
                        modifier = Modifier
                            .padding(padDp)
                            .offset { IntOffset(thumbOffsetAnim.value.roundToInt(), 0) }
                            .size(thumbSizeDp)
                            .clip(CircleShape)
                            .background(if (isHoldingAtEnd) activeThumbColor else Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isHoldingAtEnd) {
                            val ringStrokePx = with(density) { 3.dp.toPx() }
                            val ringInset = ringStrokePx / 2f + with(density) { 2.dp.toPx() }
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val arcSize = Size(size.width - ringInset * 2, size.height - ringInset * 2)
                                drawArc(
                                    color = Color.Black.copy(alpha = 0.22f),
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = Offset(ringInset, ringInset),
                                    size = arcSize,
                                    style = Stroke(width = ringStrokePx)
                                )
                                drawArc(
                                    color = Color.Black.copy(alpha = 0.85f),
                                    startAngle = -90f,
                                    sweepAngle = 360f * holdProgress,
                                    useCenter = false,
                                    topLeft = Offset(ringInset, ringInset),
                                    size = arcSize,
                                    style = Stroke(width = ringStrokePx, cap = StrokeCap.Round)
                                )
                            }
                        }
                        Icon(
                            imageVector = if (holdProgress >= 0.95f) {
                                Icons.Default.Check
                            } else if (isHoldingAtEnd) {
                                Icons.Default.LockOpen
                            } else {
                                Icons.Default.ChevronRight
                            },
                            contentDescription = "滑动释放屏幕",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun isColorLight(colorInt: Int): Boolean {
    val r = android.graphics.Color.red(colorInt) / 255.0
    val g = android.graphics.Color.green(colorInt) / 255.0
    val b = android.graphics.Color.blue(colorInt) / 255.0
    val luminance = 0.299 * r + 0.587 * g + 0.114 * b
    return luminance > 0.65
}
