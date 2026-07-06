package top.steins.autologin.ui.component

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

private val DismissEasing = CubicBezierEasing(0f, 0f, 0.4f, 1f)
private val AppearEasing = CubicBezierEasing(0.6f, 0f, 1f, 1f)

/**
 * 缩放 + 淡入淡出动画容器。
 * 内容始终参与布局（仅通过 graphicsLayer 控制视觉可见性），
 * 因此不会触发父级布局重排。
 *
 * @param visible true 时以缩放+淡入出现，false 时以缩小+淡出消失
 * @param durationMillis 动画时长
 * @param dismissScale 消失时的目标缩放比例
 * @param dismissAlpha 消失时的目标透明度
 * @param transformOrigin 缩放变换原点，默认左中
 */
@Composable
fun ScaleFadeBox(
    visible: Boolean,
    modifier: Modifier = Modifier,
    durationMillis: Int = 250,
    dismissScale: Float = 0.88f,
    dismissAlpha: Float = 0f,
    transformOrigin: TransformOrigin = TransformOrigin(0f, 0.5f),
    content: @Composable BoxScope.() -> Unit
) {
    val transition = updateTransition(targetState = visible, label = "scaleFade")

    val scale by transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = durationMillis,
                easing = if (targetState) AppearEasing else DismissEasing
            )
        },
        label = "scale"
    ) { if (it) 1f else dismissScale }

    val alpha by transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = durationMillis,
                easing = if (targetState) AppearEasing else DismissEasing
            )
        },
        label = "alpha"
    ) { if (it) 1f else dismissAlpha }

    Box(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
            this.transformOrigin = transformOrigin
        }
    ) {
        content()
    }
}
