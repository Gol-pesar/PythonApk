package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class CodeSyntaxColors(
    val editorBackground: Color,
    val gutterBackground: Color,
    val gutterText: Color,
    val terminalBackground: Color,
    val plainText: Color,
    val keyword: Color,
    val builtin: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val decorator: Color,
    val functionName: Color,
    val operator: Color,
    val prompt: Color,
    val stdout: Color,
    val stderr: Color,
    val systemInfo: Color,
)

val DarkSyntaxColors = CodeSyntaxColors(
    editorBackground = Color(0xFF0D1320),
    gutterBackground = Color(0xFF090D16),
    gutterText = Color(0xFF536480),
    terminalBackground = Color(0xFF080C14),
    plainText = Color(0xFFE2E8F0),
    keyword = Color(0xFF38BDF8),
    builtin = Color(0xFFFACC15),
    string = Color(0xFF4ADE80),
    number = Color(0xFFFB923C),
    comment = Color(0xFF64748B),
    decorator = Color(0xFFC084FC),
    functionName = Color(0xFF60A5FA),
    operator = Color(0xFF94A3B8),
    prompt = Color(0xFF38BDF8),
    stdout = Color(0xFFE2E8F0),
    stderr = Color(0xFFFB7185),
    systemInfo = Color(0xFF34D399),
)

val LightSyntaxColors = CodeSyntaxColors(
    editorBackground = Color(0xFFFFFFFF),
    gutterBackground = Color(0xFFF1F5F9),
    gutterText = Color(0xFF94A3B8),
    terminalBackground = Color(0xFF0F172A),
    plainText = Color(0xFF1E293B),
    keyword = Color(0xFF0284C7),
    builtin = Color(0xFFB45309),
    string = Color(0xFF15803D),
    number = Color(0xFFC2410C),
    comment = Color(0xFF64748B),
    decorator = Color(0xFF7E22CE),
    functionName = Color(0xFF1D4ED8),
    operator = Color(0xFF475569),
    prompt = Color(0xFF38BDF8),
    stdout = Color(0xFFF1F5F9),
    stderr = Color(0xFFFB7185),
    systemInfo = Color(0xFF34D399),
)

val LocalSyntaxColors = staticCompositionLocalOf { DarkSyntaxColors }

private val DarkColorScheme = darkColorScheme(
    primary = PythonCyan,
    onPrimary = ObsidianCanvas,
    primaryContainer = PythonCyanContainer,
    onPrimaryContainer = PythonCyanOnContainer,
    secondary = PythonAmber,
    onSecondary = ObsidianCanvas,
    secondaryContainer = PythonAmberContainer,
    onSecondaryContainer = PythonAmberOnContainer,
    tertiary = TerminalEmerald,
    onTertiary = ObsidianCanvas,
    tertiaryContainer = TerminalEmeraldContainer,
    onTertiaryContainer = TerminalEmeraldOnContainer,
    error = TracebackCoral,
    onError = ObsidianCanvas,
    errorContainer = TracebackCoralContainer,
    onErrorContainer = TracebackCoralOnContainer,
    background = ObsidianCanvas,
    onBackground = InkPrimaryDark,
    surface = ObsidianSurface,
    onSurface = InkPrimaryDark,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = InkSecondaryDark,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    outline = ObsidianBorder,
    outlineVariant = Color(0xFF1E293B),
)

private val LightColorScheme = lightColorScheme(
    primary = PythonBlueLight,
    onPrimary = Color.White,
    primaryContainer = PythonBlueLightContainer,
    onPrimaryContainer = PythonBlueLightOnContainer,
    secondary = PythonAmberLight,
    onSecondary = Color.White,
    secondaryContainer = PythonAmberLightContainer,
    onSecondaryContainer = PythonAmberLightOnContainer,
    tertiary = TerminalEmeraldLight,
    onTertiary = Color.White,
    tertiaryContainer = TerminalEmeraldLightContainer,
    onTertiaryContainer = TerminalEmeraldLightOnContainer,
    error = TracebackRedLight,
    onError = Color.White,
    errorContainer = TracebackRedLightContainer,
    onErrorContainer = TracebackRedLightOnContainer,
    background = ParchmentCanvas,
    onBackground = InkPrimaryLight,
    surface = ParchmentSurface,
    onSurface = InkPrimaryLight,
    surfaceVariant = ParchmentSurfaceVariant,
    onSurfaceVariant = InkSecondaryLight,
    surfaceContainerHigh = ParchmentSurfaceHigh,
    outline = ParchmentBorder,
    outlineVariant = Color(0xFFE2E8F0),
)

private val PyLaunchShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun PyLaunchTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val syntaxColors = if (darkTheme) DarkSyntaxColors else LightSyntaxColors

    CompositionLocalProvider(LocalSyntaxColors provides syntaxColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PyLaunchTypography,
            shapes = PyLaunchShapes,
            content = content
        )
    }
}
