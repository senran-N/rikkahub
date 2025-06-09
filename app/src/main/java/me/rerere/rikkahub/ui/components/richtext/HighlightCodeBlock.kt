package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.highlight.HighlightText
import me.rerere.highlight.Highlighter
import me.rerere.highlight.buildHighlightText
import me.rerere.rikkahub.R
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Expand
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X

import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.base64Encode

@Composable
private fun CodeCard(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    onExpandClick: () -> Unit = {}
) {
    val lines = code.lines()
    val lineCount = lines.size
    val previewLines = lines.take(3).joinToString("\n")
    
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
            .clickable { onExpandClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                                         androidx.compose.material3.Icon(
                         imageVector = Lucide.Code,
                         contentDescription = null,
                         modifier = Modifier.size(16.dp),
                         tint = MaterialTheme.colorScheme.primary
                     )
                    Text(
                        text = language.ifEmpty { "code" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (lineCount > 3) {
                        Text(
                            text = "• ${lineCount} ${stringResource(R.string.lines)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                
                                 androidx.compose.material3.Icon(
                     imageVector = Lucide.Expand,
                     contentDescription = stringResource(R.string.expand),
                     modifier = Modifier.size(16.dp),
                     tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                 )
            }
            
            // Preview
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                    .padding(10.dp)
            ) {
                Text(
                    text = previewLines,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (lineCount > 3) {
                Text(
                    text = stringResource(R.string.click_to_view_full_code),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun CodeDialog(
    code: String,
    language: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Card(
                modifier = modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth(0.95f)
                    .fillMaxSize(0.9f)
                    .clickable(enabled = false) { /* 阻止点击事件传播 */ },
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = language.ifEmpty { "Code" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${code.lines().size} ${stringResource(R.string.lines)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Copy button
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    scope.launch {
                                        clipboardManager.setClipEntry(
                                            ClipEntry(
                                                android.content.ClipData.newPlainText("code", code)
                                            )
                                        )
                                    }
                                }
                            ) {
                                                                 androidx.compose.material3.Icon(
                                     imageVector = Lucide.Copy,
                                     contentDescription = stringResource(R.string.copy),
                                     modifier = Modifier.size(20.dp)
                                 )
                            }
                            
                            // HTML preview button (if applicable)
                            if (language == "html") {
                                androidx.compose.material3.IconButton(
                                    onClick = {
                                        navController.navigate("webview?content=" + code.base64Encode())
                                        onDismiss()
                                    }
                                ) {
                                                                         androidx.compose.material3.Icon(
                                         imageVector = Lucide.Eye,
                                         contentDescription = stringResource(R.string.preview),
                                         modifier = Modifier.size(20.dp)
                                     )
                                }
                            }
                            
                            // Close button
                                                         androidx.compose.material3.IconButton(onClick = onDismiss) {
                                 androidx.compose.material3.Icon(
                                     imageVector = Lucide.X,
                                     contentDescription = stringResource(R.string.close),
                                     modifier = Modifier.size(20.dp)
                                 )
                             }
                        }
                    }
                    
                    // Code content
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        if (language == "mermaid") {
                            // Special handling for Mermaid diagrams
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(verticalScrollState)
                                    .padding(16.dp)
                            ) {
                                Mermaid(
                                    code = code,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            // Regular code display
                            SelectionContainer {
                                HighlightText(
                                    code = code,
                                    language = language,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(verticalScrollState)
                                        .horizontalScroll(horizontalScrollState)
                                        .padding(16.dp),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    colors = colorPalette,
                                    overflow = TextOverflow.Visible,
                                    softWrap = false,
                                    fontFamily = JetbrainsMono
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
) {
    val settings = LocalSettings.current
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    var showCodeDialog by remember { mutableStateOf(false) }
    
    // 检查是否应该显示为卡片模式
    val shouldShowAsCard = remember(settings.displaySetting.codeCardMode, completeCodeBlock, code) {
        settings.displaySetting.codeCardMode && 
        completeCodeBlock && 
        code.lines().size > 5 // 只有超过5行的代码才显示为卡片
    }

    if (shouldShowAsCard) {
        // 显示为卡片模式
        CodeCard(
            code = code,
            language = language,
            modifier = modifier,
            onExpandClick = { showCodeDialog = true }
        )
        
        // 代码弹窗
        if (showCodeDialog) {
            CodeDialog(
                code = code,
                language = language,
                onDismiss = { showCodeDialog = false }
            )
        }
    } else {
        // 显示为传统的代码块
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = 0.5f),
                )
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            scope.launch {
                                clipboardManager.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText("code", code),
                                    )
                                )
                            }
                        }
                        .padding(1.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.code_block_copy),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )

                    if (language == "html") {
                        Text(
                            text = stringResource(id = R.string.code_block_preview),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .clickable {
                                    navController.navigate("webview?content=" + code.base64Encode())
                                }
                        )
                    }
                }
            }
            if(completeCodeBlock && language == "mermaid") {
                Mermaid(
                    code = code,
                    modifier = Modifier.fillMaxWidth(),
                )
                return
            }
            val textStyle = LocalTextStyle.current.merge(style)
            SelectionContainer {
                HighlightText(
                    code = code,
                    language = language,
                    modifier = Modifier
                        .horizontalScroll(scrollState),
                    fontSize = textStyle.fontSize,
                    lineHeight = textStyle.lineHeight,
                    colors = colorPalette,
                    overflow = TextOverflow.Visible,
                    softWrap = false,
                    fontFamily = JetbrainsMono
                )
            }
        }
    }
}

class HighlightCodeVisualTransformation(
    val language: String,
    val highlighter: Highlighter,
    val darkMode: Boolean
): VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotatedString = try {
            val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
            if (text.text.isEmpty()) {
                AnnotatedString("")
            } else {
                runBlocking {
                    val tokens = highlighter.highlight(text.text, language)
                    buildAnnotatedString {
                        tokens.forEach { token ->
                            buildHighlightText(token, colorPalette)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AnnotatedString(text.text)
        }

        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }
}