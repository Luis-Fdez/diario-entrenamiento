package com.luis.diario.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Vista de lectura de un subconjunto de Markdown: títulos, listas, negrita, cursiva, enlaces, imágenes y separadores. */
@Composable
fun MarkdownView(markdown: String, mediaUris: Map<String, Uri>, modifier: Modifier = Modifier) {
    val image = Regex("^!\\[([^\\]]*)\\]\\(([^)]+)\\)$")
    val numbered = Regex("^(\\d+)\\.\\s+(.*)$")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        markdown.lines().forEach { raw ->
            val line = raw.trimEnd()
            val t = line.trim()
            val img = image.find(t)
            val num = numbered.find(t)
            when {
                t.isEmpty() -> Spacer(Modifier.height(4.dp))
                t == "---" || t == "***" -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                img != null -> {
                    val path = img.groupValues[2].trim()
                    val uri = mediaUris[path.removePrefix("media/")]
                    if (uri != null) {
                        AsyncImage(
                            model = uri,
                            contentDescription = img.groupValues[1].ifBlank { null },
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        Text("[imagen no encontrada: $path]", color = MaterialTheme.colorScheme.error)
                    }
                }
                t.startsWith("### ") -> Text(inline(t.removePrefix("### ")), style = MaterialTheme.typography.titleMedium)
                t.startsWith("## ") -> Text(
                    inline(t.removePrefix("## ")),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                t.startsWith("# ") -> Text(
                    inline(t.removePrefix("# ")),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                t.startsWith("- ") || t.startsWith("* ") -> Bullet("•", t.substring(2), indent = line.length - line.trimStart().length)
                num != null -> Bullet("${num.groupValues[1]}.", num.groupValues[2], indent = line.length - line.trimStart().length)
                t.startsWith("> ") -> Text(
                    inline(t.removePrefix("> ")),
                    style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
                else -> Text(inline(t), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Bullet(marker: String, text: String, indent: Int) {
    Row(Modifier.padding(start = (indent * 6).dp)) {
        Text(marker, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(inline(text), style = MaterialTheme.typography.bodyLarge)
    }
}

private val INLINE = Regex("\\*\\*(.+?)\\*\\*|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)|_(.+?)_|\\*(.+?)\\*")

@Composable
private fun inline(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var pos = 0
        INLINE.findAll(text).forEach { m ->
            append(text.substring(pos, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
                g[2].isNotEmpty() -> withLink(
                    LinkAnnotation.Url(
                        g[3],
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(g[2]) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
            }
            pos = m.range.last + 1
        }
        if (pos < text.length) append(text.substring(pos))
    }
}
