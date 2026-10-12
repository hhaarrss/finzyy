package com.smartspend.app.ui.components

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartspend.app.ui.theme.FinzyyTheme

/*
 * The user's profile picture: the first letter of their name by default, or one of six
 * illustrated avatars they pick on the Edit profile screen.
 *
 * The choice is kept on this phone only (no photo is taken or uploaded, and nothing is sent to
 * the server). It is stored with the account it belongs to, so a different person signing in on
 * the same phone starts with their own initial.
 */

private enum class Hair { Short, SidePart, Curly, Long, Bun, Bob }

private class AvatarSpec(
    val id: String,
    val label: String,
    val ground: Color,
    val skin: Color,
    val hair: Color,
    val shirt: Color,
    val style: Hair,
    val glasses: Boolean = false,
    val beard: Boolean = false,
    val earrings: Boolean = false
)

private val Avatars = listOf(
    AvatarSpec("m1", "Avatar 1, short hair", Color(0xFFE9DDC4), Color(0xFFF2CFAE), Color(0xFF2A2622), Color(0xFF22262B), Hair.Short),
    AvatarSpec("m2", "Avatar 2, beard", Color(0xFFD5DDD0), Color(0xFFDBA571), Color(0xFF3A2618), Color(0xFF9C7B43), Hair.SidePart, beard = true),
    AvatarSpec("m3", "Avatar 3, curly hair and glasses", Color(0xFFD3D9E0), Color(0xFFA9713F), Color(0xFF1E1916), Color(0xFF4F5F6E), Hair.Curly, glasses = true),
    AvatarSpec("f1", "Avatar 4, long hair", Color(0xFFE9D6D2), Color(0xFFF2CFAE), Color(0xFF5A3620), Color(0xFF9C7B43), Hair.Long),
    AvatarSpec("f2", "Avatar 5, hair in a bun", Color(0xFFE5DFD3), Color(0xFFDBA571), Color(0xFF2A2622), Color(0xFF4F5F6E), Hair.Bun, earrings = true),
    AvatarSpec("f3", "Avatar 6, bob and glasses", Color(0xFFDCD6E6), Color(0xFFA9713F), Color(0xFF1E1916), Color(0xFF22262B), Hair.Bob, glasses = true),
)

/** Where the choice lives. [current] is Compose state, so every screen showing it updates at once. */
object ProfileAvatarStore {
    private const val PREFS = "smart_spend_prefs"
    private const val KEY = "profile_avatar"
    private const val KEY_OWNER = "profile_avatar_owner"

    var current by mutableStateOf<String?>(null)
        private set

    private fun owner(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (prefs.getString("user_phone", null) ?: prefs.getString("user_email", null))?.takeIf { it.isNotBlank() }
    }

    /** Reads the saved choice, ignoring one that belongs to a different account. */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY, null)?.takeIf { id -> Avatars.any { it.id == id } }
        val savedFor = prefs.getString(KEY_OWNER, null)
        val me = owner(context)
        current = if (saved != null && (savedFor == null || me == null || savedFor == me)) saved else null
    }

    /** [id] = null goes back to the initial. */
    fun set(context: Context, id: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (id == null) edit.remove(KEY).remove(KEY_OWNER) else edit.putString(KEY, id).putString(KEY_OWNER, owner(context))
        edit.apply()
        current = id
    }
}

/** The signed-in user's saved avatar id (null = show the initial). */
@Composable
fun rememberProfileAvatar(): String? {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ProfileAvatarStore.load(context) }
    return ProfileAvatarStore.current
}

/** The profile picture at any size: a chosen avatar, or the first letter of [name]. */
@Composable
fun ProfileAvatar(name: String?, size: Dp, modifier: Modifier = Modifier, avatarId: String? = rememberProfileAvatar()) {
    val spec = Avatars.firstOrNull { it.id == avatarId }
    if (spec != null) {
        AvatarArt(spec, modifier.size(size))
    } else {
        Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                name?.trim()?.firstOrNull()?.uppercase() ?: "",
                fontSize = (size.value * 0.42f).sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Black
            )
        }
    }
}

/** [ProfileAvatar] with a pencil badge; tapping it opens the picker. Used on Edit profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditableProfileAvatar(name: String?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current = rememberProfileAvatar()
    var picking by androidx.compose.runtime.remember { mutableStateOf(false) }

    Box(
        modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Change profile picture") { picking = true }
            .padding(2.dp)
    ) {
        ProfileAvatar(name, size, avatarId = current)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(size * 0.38f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.19f)
            )
        }
    }

    if (picking) {
        ModalBottomSheet(
            onDismissRequest = { picking = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Choose your picture", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Pick an avatar, or keep the first letter of your name. It is saved on this phone only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FinzyyTheme.colors.inkMuted
                )
                fun choose(id: String?) {
                    ProfileAvatarStore.set(context, id)
                    picking = false
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Choice(selected = current == null, label = "Use my initial", modifier = Modifier.size(72.dp), onClick = { choose(null) }) {
                        ProfileAvatar(name, 64.dp, avatarId = null)
                    }
                    Spacer(Modifier.size(14.dp))
                    Text("My initial", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                Avatars.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { spec ->
                            Choice(
                                selected = current == spec.id,
                                label = spec.label,
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                onClick = { choose(spec.id) }
                            ) {
                                AvatarArt(spec, Modifier.fillMaxWidth().aspectRatio(1f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** One option in the picker: a gold ring marks the current choice. */
@Composable
private fun Choice(selected: Boolean, label: String, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) FinzyyTheme.colors.accent else FinzyyTheme.colors.hairline,
                shape = CircleShape
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Tiny curls for the curly style: centre x, centre y, radius, on the 100 x 100 grid. */
private val Curls = listOf(Triple(35f, 31f, 7f), Triple(41f, 25.5f, 7f), Triple(50f, 23f, 7.2f), Triple(59f, 25.5f, 7f), Triple(65f, 31f, 7f), Triple(32.5f, 38f, 5.6f), Triple(67.5f, 38f, 5.6f), Triple(45.5f, 29.5f, 6f), Triple(54.5f, 29.5f, 6f))

/**
 * Flat illustrated portrait, drawn on a 100 x 100 grid and clipped to a circle. The shapes are
 * generated from one design file, so the numbers below are not meant to be edited by hand.
 */
@Composable
private fun AvatarArt(spec: AvatarSpec, modifier: Modifier = Modifier) {
    val ink = Color(0xFF1E1A18)
    Canvas(
        modifier
            .clip(CircleShape)
            .background(spec.ground)
            .clearAndSetSemantics { contentDescription = spec.label }
    ) {
        val u = size.minDimension / 100f
        fun at(x: Float, y: Float) = Offset(x * u, y * u)
        fun oval(color: Color, cx: Float, cy: Float, rx: Float, ry: Float, style: androidx.compose.ui.graphics.drawscope.DrawStyle = androidx.compose.ui.graphics.drawscope.Fill) =
            drawOval(color, at(cx - rx, cy - ry), Size(rx * 2 * u, ry * 2 * u), style = style)
        fun tone(c: Color, r: Float, g: Float, b: Float) = Color(red = c.red * r, green = c.green * g, blue = c.blue * b)
        val shade = tone(spec.skin, 0.87f, 0.82f, 0.78f)
        val blush = Color(0x3CE27864)

        // Behind the head and shoulders.
        if (spec.style == Hair.Bun) oval(spec.hair, 50f, 14.5f, 8.5f, 8.5f)
        drawPath(Path().apply { moveTo(10f * u, 100f * u); cubicTo(10f * u, 83f * u, 27f * u, 75f * u, 43f * u, 72f * u); lineTo(57f * u, 72f * u); cubicTo(73f * u, 75f * u, 90f * u, 83f * u, 90f * u, 100f * u); close() }, spec.shirt)
        if (spec.style == Hair.Long) drawPath(Path().apply { moveTo(29.5f * u, 46f * u); cubicTo(26f * u, 22f * u, 41f * u, 16.5f * u, 50f * u, 16.5f * u); cubicTo(59f * u, 16.5f * u, 74f * u, 22f * u, 70.5f * u, 46f * u); lineTo(74f * u, 82f * u); cubicTo(67f * u, 87f * u, 60f * u, 83f * u, 60f * u, 75f * u); lineTo(40f * u, 75f * u); cubicTo(40f * u, 83f * u, 33f * u, 87f * u, 26f * u, 82f * u); close() }, spec.hair)
        if (spec.style == Hair.Bob) drawPath(Path().apply { moveTo(29.5f * u, 46f * u); cubicTo(26f * u, 22f * u, 41f * u, 16.5f * u, 50f * u, 16.5f * u); cubicTo(59f * u, 16.5f * u, 74f * u, 22f * u, 70.5f * u, 46f * u); lineTo(72f * u, 63f * u); cubicTo(67f * u, 68f * u, 61f * u, 66f * u, 60f * u, 61f * u); lineTo(40f * u, 61f * u); cubicTo(39f * u, 66f * u, 33f * u, 68f * u, 28f * u, 63f * u); close() }, spec.hair)

        oval(shade, 50f, 73f, 8.5f, 5.5f)                                                   // neckline
        drawRoundRect(shade, at(44f, 58f), Size(12f * u, 17f * u), CornerRadius(5f * u, 5f * u))   // neck
        oval(shade, 32.3f, 46.5f, 3.0f, 4.2f)                                               // ears
        oval(shade, 67.7f, 46.5f, 3.0f, 4.2f)
        oval(spec.skin, 50f, 44f, 17.6f, 20.5f)                                             // head
        oval(blush, 39.5f, 52.5f, 3.4f, 2.6f)                                               // cheeks
        oval(blush, 60.5f, 52.5f, 3.4f, 2.6f)

        if (spec.beard) drawPath(Path().apply { moveTo(32.6f * u, 47f * u); cubicTo(32f * u, 62f * u, 41f * u, 68.5f * u, 50f * u, 68.5f * u); cubicTo(59f * u, 68.5f * u, 68f * u, 62f * u, 67.4f * u, 47f * u); cubicTo(66f * u, 54f * u, 62f * u, 57.5f * u, 57f * u, 57f * u); cubicTo(54f * u, 55f * u, 46f * u, 55f * u, 43f * u, 57f * u); cubicTo(38f * u, 57.5f * u, 34f * u, 54f * u, 32.6f * u, 47f * u); close() }, spec.hair)
        when (spec.style) {
            Hair.Short -> drawPath(Path().apply { moveTo(31.5f * u, 45f * u); cubicTo(28f * u, 26f * u, 40f * u, 19f * u, 50f * u, 19f * u); cubicTo(60f * u, 19f * u, 72f * u, 26f * u, 68.5f * u, 45f * u); cubicTo(67f * u, 38f * u, 64f * u, 33f * u, 58f * u, 31f * u); cubicTo(50f * u, 29f * u, 41f * u, 31f * u, 36f * u, 34f * u); cubicTo(33f * u, 37f * u, 32f * u, 41f * u, 31.5f * u, 45f * u); close() }, spec.hair)
            Hair.SidePart -> drawPath(Path().apply { moveTo(31.5f * u, 44f * u); cubicTo(28f * u, 24f * u, 42f * u, 18f * u, 52f * u, 19f * u); cubicTo(64f * u, 20f * u, 72f * u, 28f * u, 68.5f * u, 44f * u); cubicTo(67f * u, 37f * u, 64f * u, 33f * u, 61f * u, 31f * u); cubicTo(53f * u, 35f * u, 43f * u, 34f * u, 37f * u, 30f * u); cubicTo(34f * u, 33f * u, 32f * u, 38f * u, 31.5f * u, 44f * u); close() }, spec.hair)
            Hair.Curly -> Curls.forEach { (x, y, r) -> oval(spec.hair, x, y, r, r) }
            Hair.Long -> drawPath(Path().apply { moveTo(32f * u, 43f * u); cubicTo(30f * u, 27f * u, 41f * u, 20.5f * u, 50f * u, 20.5f * u); cubicTo(59f * u, 20.5f * u, 70f * u, 27f * u, 68f * u, 43f * u); cubicTo(65f * u, 35f * u, 58f * u, 30f * u, 49f * u, 30f * u); cubicTo(43f * u, 33f * u, 37f * u, 38f * u, 32f * u, 43f * u); close() }, spec.hair)
            Hair.Bun -> drawPath(Path().apply { moveTo(31.8f * u, 44f * u); cubicTo(29f * u, 26f * u, 40f * u, 20f * u, 50f * u, 20f * u); cubicTo(60f * u, 20f * u, 71f * u, 26f * u, 68.2f * u, 44f * u); cubicTo(66f * u, 36f * u, 60f * u, 30.5f * u, 50f * u, 30.5f * u); cubicTo(40f * u, 30.5f * u, 34f * u, 36f * u, 31.8f * u, 44f * u); close() }, spec.hair)
            Hair.Bob -> drawPath(Path().apply { moveTo(32f * u, 42.5f * u); cubicTo(30.5f * u, 27f * u, 41f * u, 20.5f * u, 50f * u, 20.5f * u); cubicTo(59f * u, 20.5f * u, 69.5f * u, 27f * u, 68f * u, 42.5f * u); cubicTo(60f * u, 37.5f * u, 40f * u, 37.5f * u, 32f * u, 42.5f * u); close() }, spec.hair)
        }

        // Face.
        val brow = 1.5f * u
        drawLine(spec.hair, at(40.6f, 41.3f), at(45.6f, 40.5f), strokeWidth = brow, cap = StrokeCap.Round)
        drawLine(spec.hair, at(54.4f, 40.5f), at(59.4f, 41.3f), strokeWidth = brow, cap = StrokeCap.Round)
        oval(ink, 43.4f, 46.3f, 1.8f, 2.2f)
        oval(ink, 56.6f, 46.3f, 1.8f, 2.2f)
        oval(Color.White, 44.0f, 45.6f, 0.55f, 0.55f)
        oval(Color.White, 57.2f, 45.6f, 0.55f, 0.55f)
        drawArc(                                                                            // nose
            tone(spec.skin, 0.74f, 0.66f, 0.6f), 20f, 140f, useCenter = false,
            topLeft = at(48.1f, 48.3f), size = Size(3.8f * u, 3.8f * u), style = Stroke(1.2f * u, cap = StrokeCap.Round)
        )
        drawArc(                                                                            // smile
            if (spec.beard) Color.White else ink, 18f, 144f, useCenter = false,
            topLeft = at(45f, 49.6f), size = Size(10f * u, 7.6f * u), style = Stroke(1.6f * u, cap = StrokeCap.Round)
        )
        if (spec.glasses) {
            oval(ink, 43.4f, 46.3f, 5.6f, 5.6f, Stroke(1.4f * u))
            oval(ink, 56.6f, 46.3f, 5.6f, 5.6f, Stroke(1.4f * u))
            drawLine(ink, at(49.0f, 46.0f), at(51.0f, 46.0f), strokeWidth = 1.3f * u, cap = StrokeCap.Round)
        }
        if (spec.earrings) {
            oval(Color(0xFFB8965A), 32.0f, 52.2f, 1.5f, 1.5f)
            oval(Color(0xFFB8965A), 68.0f, 52.2f, 1.5f, 1.5f)
        }
    }
}
