package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.EditorSettings
import com.example.ui.PyLaunchUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.roundToInt

private data class RuntimeCapability(
    val badge: String,
    val titleFa: String,
    val descFa: String,
    val demoSnippet: String
)

private val NativeRuntimeCapabilities = listOf(
    RuntimeCapability(
        badge = "PyPI",
        titleFa = "بسته رسمی websockets",
        descFa = "نسخه کامل و رسمی PyPI برای کلاینت و سرور WebSocket (wss:// و ws://) با سرعت نیتیو.",
        demoSnippet = "import websockets; f'websockets {websockets.__version__}'"
    ),
    RuntimeCapability(
        badge = "Async",
        titleFa = "هسته واقعی asyncio و سوکت نیتیو",
        descFa = "اجرای پرسرعت event loop بومی CPython با TCP_NODELAY، TLS/SNI و همزمانی واقعی.",
        demoSnippet = "import asyncio, socket; asyncio.run(asyncio.sleep(0.01, result='asyncio OK'))"
    ),
    RuntimeCapability(
        badge = "TLS / SSL",
        titleFa = "گواهی‌نامه‌های certifi + OpenSSL",
        descFa = "احراز هویت امن HTTPS و WSS روی همه دستگاه‌های اندروید با بسته certifi به‌روز.",
        demoSnippet = "import ssl, certifi; (ssl.OPENSSL_VERSION, certifi.where())"
    ),
    RuntimeCapability(
        badge = "HTTP",
        titleFa = "بسته رسمی requests",
        descFa = "ارسال درخواست‌های HTTP/HTTPS با پشتیبانی کامل از JSON، هدرها و نشست‌ها.",
        demoSnippet = "import requests; f'requests {requests.__version__}'"
    ),
    RuntimeCapability(
        badge = "Crypto",
        titleFa = "رمزنگاری سریع C (hashlib و hmac)",
        descFa = "توابع SHA-256، HMAC، struct و تولید بایت تصادفی با سرعت بومی زبان C.",
        demoSnippet = "import hashlib, hmac; hmac.new(b'key', b'OK', hashlib.sha256).hexdigest()[:16]"
    ),
    RuntimeCapability(
        badge = "Stdlib",
        titleFa = "کتابخانه استاندارد کامل CPython",
        descFa = "دسترسی کامل به sqlite3، json، re، pathlib، threading، urllib، zlib و صدها ماژول دیگر.",
        demoSnippet = "import sqlite3, sys; (sys.implementation.name, sqlite3.sqlite_version)"
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuntimeScreen(
    uiState: PyLaunchUiState,
    settings: EditorSettings,
    onTryInRepl: (String) -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit,
    onFontSizeChanged: (Int) -> Unit,
    onTabSizeChanged: (Int) -> Unit,
    onWordWrapChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val info = uiState.runtimeInfo

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Banner Card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(212.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_python_hero),
                        contentDescription = stringResource(R.string.cd_hero_banner),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x660B0F17),
                                        Color(0xEE0B0F17)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "${info.implementation} ${info.pythonVersion} • Native Android",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.runtime_hero_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White
                            )
                            Text(
                                text = stringResource(R.string.runtime_hero_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://pypi.org/project/websockets/")
                                    )
                                    context.startActivity(intent)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("open_python_release_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.action_open_release_page),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }

            // Runtime Specifications Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.runtime_specs_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        SpecRow("موتور اجرا", "${info.implementation} ${info.pythonVersion} (Native C)")
                        SpecRow("بسته websockets", "v${info.websocketsVersion} (PyPI)")
                        SpecRow("بسته requests", "v${info.requestsVersion} (PyPI)")
                        SpecRow("موتور TLS / SSL", info.opensslVersion)
                        SpecRow(
                            "سرویس پس‌زمینه اندروید",
                            if (uiState.isBackgroundServiceActive) "فعال (Foreground + WakeLock)" else "آماده (بهینه‌سازی خودکار هنگام اجرا)"
                        )
                        SpecRow("نسخه اندروید دستگاه", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        SpecRow("معماری پردازنده", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
                    }
                }
            }

            // Native Capabilities Showcase
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = stringResource(R.string.runtime_features_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NativeRuntimeCapabilities.forEach { feat ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = feat.titleFa,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = feat.badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = feat.descFa,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = { onTryInRepl(feat.demoSnippet) },
                                        modifier = Modifier.testTag("try_cap_${feat.badge.replace(" ", "_")}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("تست در کنسول")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Installed PyPI Packages Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = stringResource(R.string.runtime_modules_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        val displayPackages = if (info.packages.isNotEmpty()) {
                            info.packages
                        } else {
                            listOf("websockets", "requests", "certifi", "urllib3", "idna", "charset-normalizer")
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            displayPackages.forEach { pkgSpec ->
                                val pkgImport = pkgSpec.substringBefore("==").replace("-", "_")
                                Surface(
                                    onClick = { onTryInRepl("import $pkgImport; dir($pkgImport)[:12]") },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = pkgSpec,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Editor & Appearance Settings Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.runtime_settings_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        // Dark Theme Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DarkMode,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = stringResource(R.string.setting_dark_theme),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Switch(
                                checked = settings.isDarkTheme,
                                onCheckedChange = onDarkThemeChanged,
                                modifier = Modifier.testTag("setting_dark_theme_switch")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Font Size Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.setting_font_size),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "${settings.fontSizeSp} sp",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = settings.fontSizeSp.toFloat(),
                                onValueChange = { onFontSizeChanged(it.roundToInt()) },
                                valueRange = 11f..22f,
                                steps = 10,
                                modifier = Modifier.testTag("setting_font_size_slider")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Tab Size Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.setting_tab_size),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(2, 4).forEach { spaces ->
                                    val selected = settings.tabSize == spaces
                                    Surface(
                                        onClick = { onTabSizeChanged(spaces) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = "$spaces فضا",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Word Wrap Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.setting_word_wrap),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(
                                checked = settings.wordWrap,
                                onCheckedChange = onWordWrapChanged,
                                modifier = Modifier.testTag("setting_word_wrap_switch")
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
