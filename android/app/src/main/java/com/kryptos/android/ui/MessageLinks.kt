package com.kryptos.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

class WebLink(val url: String, val start: Int, val end: Int)

object MessageLinks {
    private const val SCAN_LIMIT = 20_000
    private const val PHONE_SEPARATORS = " -()./\u00A0\u2011"
    private const val BARE_PHONE_MIN_DIGITS = 10

    private val barePhone = Regex(
        "(?<![\\p{L}\\d+])(?:\\+?\\d{10,15}|\\+?\\d{1,4}(?:[ \\-.()]{1,2}\\d{1,4}){2,6})(?![\\p{L}\\d])")

    fun target(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> web(url)
            lower.startsWith("mailto:") -> mail(url.substring(7))
            lower.startsWith("tel:") -> phone(url.substring(4))
            else -> null
        }
    }

    fun find(text: String): List<WebLink> {
        if (text.isEmpty()) return emptyList()
        val scanned = if (text.length > SCAN_LIMIT) text.substring(0, SCAN_LIMIT) else text
        val spannable = SpannableString(scanned)
        val mask = Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS
        val linked = runCatching { Linkify.addLinks(spannable, mask) }.getOrDefault(false)
        val found = mutableListOf<WebLink>()
        if (linked) {
            spannable.getSpans(0, scanned.length, URLSpan::class.java).forEach { span ->
                val url = span.url?.let { target(it) } ?: return@forEach
                val start = spannable.getSpanStart(span)
                val end = spannable.getSpanEnd(span)
                if (start >= 0 && end > start) found.add(WebLink(url, start, end))
            }
        }
        for (bare in bareNumbers(scanned)) {
            if (found.none { bare.start < it.end && it.start < bare.end }) found.add(bare)
        }
        return found.sortedBy { it.start }
    }

    fun bareNumbers(text: String): List<WebLink> =
        barePhone.findAll(text).mapNotNull { match ->
            if (match.value.count { it in '0'..'9' } < BARE_PHONE_MIN_DIGITS) return@mapNotNull null
            val url = target("tel:" + match.value) ?: return@mapNotNull null
            WebLink(url, match.range.first, match.range.last + 1)
        }.toList()

    fun open(context: Context, url: String): Boolean {
        val target = target(url) ?: return false
        val uri = runCatching { Uri.parse(target) }.getOrNull() ?: return false
        val intent = when (uri.scheme?.lowercase()) {
            "http", "https" -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            "mailto" -> Intent(Intent.ACTION_SENDTO, uri)
            "tel" -> Intent(Intent.ACTION_DIAL, uri)
            else -> return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun styled(text: String, color: Color, links: List<WebLink>): AnnotatedString {
        if (links.isEmpty()) return AnnotatedString(text)
        val style = SpanStyle(color = color, textDecoration = TextDecoration.Underline)
        return buildAnnotatedString {
            append(text)
            for (link in links) addStyle(style, link.start, link.end)
        }
    }

    fun hit(links: List<WebLink>, layout: TextLayoutResult?, position: Offset): String? {
        if (links.isEmpty() || layout == null) return null
        if (position.y < 0f || position.y > layout.size.height.toFloat()) return null
        val line = layout.getLineForVerticalPosition(position.y)
        if (position.x < layout.getLineLeft(line) || position.x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForPosition(position)
        return links.firstOrNull { offset >= it.start && offset < it.end }?.url
    }

    fun annotated(text: String, color: Color, onOpen: (String) -> Unit): AnnotatedString {
        val links = find(text)
        if (links.isEmpty()) return AnnotatedString(text)
        val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
        return buildAnnotatedString {
            append(text)
            for (link in links) {
                addLink(LinkAnnotation.Url(link.url, styles) { onOpen(link.url) }, link.start, link.end)
            }
        }
    }

    fun spanned(text: String, color: Int, onOpen: (String) -> Unit): CharSequence {
        val links = find(text)
        if (links.isEmpty()) return text
        val out = SpannableString(text)
        for (link in links) {
            val span = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onOpen(link.url)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = color
                    ds.isUnderlineText = true
                }
            }
            out.setSpan(span, link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun web(url: String): String? {
        val rest = url.substring(url.indexOf("//") + 2)
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (cut < 0) rest else rest.substring(0, cut)
        return if (authority.isNotEmpty() && !authority.contains('@')) url else null
    }

    private fun mail(rest: String): String? {
        val address = rest.takeWhile { it != '?' && it != '#' }
        return if (isAddress(address)) "mailto:$address" else null
    }

    private fun phone(rest: String): String? {
        val digits = StringBuilder()
        var international = false
        for ((index, character) in rest.withIndex()) {
            when {
                character == '+' -> if (index == 0) international = true else return null
                character in '0'..'9' -> digits.append(character)
                PHONE_SEPARATORS.indexOf(character) >= 0 -> Unit
                else -> return null
            }
        }
        if (digits.length !in 5..15) return null
        return if (international) "tel:+$digits" else "tel:$digits"
    }

    private fun isAddress(value: String): Boolean {
        val at = value.indexOf('@')
        if (at <= 0 || at != value.lastIndexOf('@')) return false
        val local = value.substring(0, at)
        val domain = value.substring(at + 1)
        if (local.length > 64 || domain.length !in 4..255) return false
        if (!local.all { it.isAsciiLetterOrDigit() || it in "._%+-" }) return false
        if (!domain.all { it.isAsciiLetterOrDigit() || it == '.' || it == '-' }) return false
        val dot = domain.lastIndexOf('.')
        if (dot < 1 || domain.length - dot < 3) return false
        return !domain.startsWith(".") && !domain.startsWith("-") &&
            !domain.endsWith("-") && !domain.contains("..")
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

@Composable
fun linkedText(text: String, color: Color): AnnotatedString {
    val context = LocalContext.current
    return remember(text, color, context) {
        MessageLinks.annotated(text, color) { url -> MessageLinks.open(context, url) }
    }
}
