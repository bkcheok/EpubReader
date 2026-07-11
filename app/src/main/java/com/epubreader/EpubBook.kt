package com.epubreader

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * Represents an EPUB book with metadata and chapters
 */
data class EpubBook(
    val title: String = "Unknown Title",
    val author: String = "Unknown Author",
    val language: String = "en",
    val identifier: String = "",
    val coverImagePath: String? = null,
    val chapters: List<EpubChapter> = emptyList(),
    val spine: List<String> = emptyList(),
    val toc: List<TocItem> = emptyList(),
    val basePath: String = "",
    val manifest: Map<String, ManifestItem> = emptyMap()
)

data class EpubChapter(
    val id: String,
    val title: String,
    val href: String,
    val content: String = "",
    val order: Int = 0
)

data class TocItem(
    val title: String,
    val href: String,
    val children: List<TocItem> = emptyList(),
    val level: Int = 0
)

data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String = ""
)

class EpubParser(private val context: Context) {

    companion object {
        private const val TAG = "EpubParser"
        private const val EPUB_MIME = "application/epub+zip"
        private const val NCX_MIME = "application/x-dtbncx+xml"
        private const val NAV_MIME = "application/x-dtbncx+xml"
        private const val XHTML_MIME = "application/xhtml+xml"
        private const val HTML_MIME = "text/html"
    }

    /**
     * Parse EPUB from URI
     */
    @Throws(Exception::class)
    fun parseFromUri(uri: Uri): EpubBook {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open URI: $uri")
        
        val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.epub")
        tempFile.deleteOnExit()
        
        FileOutputStream(tempFile).use { output ->
            inputStream.copyTo(output)
        }
        
        return parseFromFile(tempFile)
    }

    /**
     * Parse EPUB from File
     */
    @Throws(Exception::class)
    fun parseFromFile(file: File): EpubBook {
        val zipFile = ZipFile(file)
        try {
            // Find container.xml to locate OPF file
            val containerEntry = zipFile.getEntry("META-INF/container.xml")
                ?: throw Exception("No container.xml found in EPUB")
            
            val containerXml = zipFile.getInputStream(containerEntry).readBytes().decodeToString()
            val containerDoc = Jsoup.parse(containerXml, "", org.jsoup.parser.Parser.xmlParser())
            val rootfile = containerDoc.select("rootfile").first()
                ?: throw Exception("No rootfile in container.xml")
            
            val opfPath = rootfile.attr("full-path")
            val opfEntry = zipFile.getEntry(opfPath)
                ?: throw Exception("OPF file not found: $opfPath")
            
            val opfContent = zipFile.getInputStream(opfEntry).readBytes().decodeToString()
            val opfDoc = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())
            
            // Parse metadata
            val metadata = opfDoc.select("metadata").first()
            val title = metadata?.select("title").first()?.text() ?: file.nameWithoutExtension
            val author = metadata?.select("creator").first()?.text() ?: "Unknown Author"
            val language = metadata?.select("language").first()?.text() ?: "en"
            val identifier = metadata?.select("identifier").first()?.text() ?: file.name
            
            // Parse manifest
            val manifestItems = mutableMapOf<String, ManifestItem>()
            opfDoc.select("manifest item").forEach { item ->
                val id = item.attr("id")
                val href = item.attr("href")
                val mediaType = item.attr("media-type")
                val properties = item.attr("properties")
                manifestItems[id] = ManifestItem(id, href, mediaType, properties)
            }
            
            // Parse spine
            val spineItems = mutableListOf<String>()
            opfDoc.select("spine itemref").forEach { itemref ->
                val idref = itemref.attr("idref")
                spineItems.add(idref)
            }
            
            // Parse cover
            val coverImagePath = parseCoverImage(opfDoc, manifestItems, zipFile, opfPath)
            
            // Parse NCX or NAV for TOC
            val toc = parseToc(opfDoc, manifestItems, zipFile, opfPath)
            
            // Parse chapters from spine
            val basePath = opfPath.substringBeforeLast('/') + "/"
            val chapters = parseChapters(spineItems, manifestItems, zipFile, basePath, toc)
            
            return EpubBook(
                title = title,
                author = author,
                language = language,
                identifier = identifier,
                coverImagePath = coverImagePath,
                chapters = chapters,
                spine = spineItems,
                toc = toc,
                basePath = basePath,
                manifest = manifestItems
            )
        } finally {
            zipFile.close()
        }
    }

    private fun parseCoverImage(
        opfDoc: Document,
        manifestItems: Map<String, ManifestItem>,
        zipFile: ZipFile,
        opfPath: String
    ): String? {
        // Check meta for cover
        val coverMeta = opfDoc.select("meta[name=cover]").firstOrNull()
        val coverId = coverMeta?.attr("content")
        
        if (coverId != null && coverId.isNotEmpty()) {
            manifestItems[coverId]?.let { item ->
                return extractFile(zipFile, opfPath, item.href)
            }
        }
        
        // Check manifest item with properties="cover-image"
        manifestItems.values.firstOrNull { it.properties.contains("cover-image") }?.let { item ->
            return extractFile(zipFile, opfPath, item.href)
        }
        
        // Try first image
        manifestItems.values.firstOrNull { it.mediaType.startsWith("image/") }?.let { item ->
            return extractFile(zipFile, opfPath, item.href)
        }
        
        return null
    }

    private fun extractFile(zipFile: ZipFile, basePath: String, href: String): String? {
        val fullPath = if (href.startsWith("/")) href.substring(1) else basePath + href
        val entry = zipFile.getEntry(fullPath)
        return if (entry != null) {
            val cacheFile = File(context.cacheDir, "epub_${System.currentTimeMillis()}_${entry.name.substringAfterLast('/')}")
            cacheFile.deleteOnExit()
            FileOutputStream(cacheFile).use { output ->
                zipFile.getInputStream(entry).copyTo(output)
            }
            cacheFile.absolutePath
        } else null
    }

    private fun parseToc(
        opfDoc: Document,
        manifestItems: Map<String, ManifestItem>,
        zipFile: ZipFile,
        opfPath: String
    ): List<TocItem> {
        // Try NAV (EPUB3) first
        val navItem = manifestItems.values.firstOrNull { it.properties.contains("nav") }
            ?: manifestItems.values.firstOrNull { it.mediaType == NAV_MIME }
        
        if (navItem != null) {
            val navPath = opfPath.substringBeforeLast('/') + "/" + navItem.href
            val navEntry = zipFile.getEntry(navPath)
            if (navEntry != null) {
                val navContent = zipFile.getInputStream(navEntry).readBytes().decodeToString()
                return parseNavToc(navContent, opfPath.substringBeforeLast('/') + "/")
            }
        }
        
        // Fallback to NCX (EPUB2)
        val ncxItem = manifestItems.values.firstOrNull { it.mediaType == NCX_MIME }
        if (ncxItem != null) {
            val ncxPath = opfPath.substringBeforeLast('/') + "/" + ncxItem.href
            val ncxEntry = zipFile.getEntry(ncxPath)
            if (ncxEntry != null) {
                val ncxContent = zipFile.getInputStream(ncxEntry).readBytes().decodeToString()
                return parseNcxToc(ncxContent)
            }
        }
        
        return emptyList()
    }

    private fun parseNavToc(navContent: String, basePath: String): List<TocItem> {
        val doc = Jsoup.parse(navContent, "", org.jsoup.parser.Parser.xmlParser())
        val nav = doc.select("nav[epub\\:type=toc], nav[typeof=toc]").first()
            ?: doc.select("nav").first()
        
        return if (nav != null) {
            parseNavList(nav.select("ol > li"), basePath, 0)
        } else emptyList()
    }

    private fun parseNavList(items: Elements, basePath: String, level: Int): List<TocItem> {
        return items.map { li ->
            val link = li.select("a").first()
            val title = link?.text() ?: li.text()
            val href = link?.attr("href") ?: ""
            val fullHref = if (href.isNotEmpty()) basePath + href else ""
            val children = li.select("ol > li")
            val childItems = if (children.isNotEmpty()) {
                parseNavList(children, basePath, level + 1)
            } else emptyList()
            
            TocItem(title, fullHref, childItems, level)
        }
    }

    private fun parseNcxToc(ncxContent: String): List<TocItem> {
        val doc = Jsoup.parse(ncxContent, "", org.jsoup.parser.Parser.xmlParser())
        val navMap = doc.select("navMap").first()
        return if (navMap != null) {
            parseNcxNavPoints(navMap.select("navPoint"), 0)
        } else emptyList()
    }

    private fun parseNcxNavPoints(navPoints: Elements, level: Int): List<TocItem> {
        return navPoints.map { navPoint ->
            val label = navPoint.select("navLabel > text").first()?.text() ?: ""
            val content = navPoint.select("content").first()
            val href = content?.attr("src") ?: ""
            val children = navPoint.select("navPoint")
            val childItems = if (children.isNotEmpty()) {
                parseNcxNavPoints(children, level + 1)
            } else emptyList()
            
            TocItem(label, href, childItems, level)
        }
    }

    private fun parseChapters(
        spineItems: List<String>,
        manifestItems: Map<String, ManifestItem>,
        zipFile: ZipFile,
        basePath: String,
        toc: List<TocItem>
    ): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()
        var order = 0
        
        spineItems.forEach { idref ->
            manifestItems[idref]?.let { item ->
                if (item.mediaType == XHTML_MIME || item.mediaType == HTML_MIME) {
                    val fullPath = basePath + item.href
                    val entry = zipFile.getEntry(fullPath)
                    if (entry != null) {
                        val content = zipFile.getInputStream(entry).readBytes().decodeToString()
                        val title = findChapterTitle(toc, item.href) ?: extractTitleFromContent(content) ?: "Chapter ${order + 1}"
                        chapters.add(EpubChapter(
                            id = idref,
                            title = title,
                            href = item.href,
                            content = cleanHtmlContent(content),
                            order = order
                        ))
                        order++
                    }
                }
            }
        }
        
        return chapters
    }

    private fun findChapterTitle(toc: List<TocItem>, href: String): String? {
        fun search(items: List<TocItem>): String? {
            for (item in items) {
                if (item.href.contains(href) || href.contains(item.href)) {
                    return item.title
                }
                val found = search(item.children)
                if (found != null) return found
            }
            return null
        }
        return search(toc)
    }

    private fun extractTitleFromContent(content: String): String? {
        val doc = Jsoup.parse(content, "", org.jsoup.parser.Parser.xmlParser())
        return doc.select("title").first()?.text()
            ?: doc.select("h1, h2, h3").first()?.text()
    }

    private fun cleanHtmlContent(html: String): String {
        val doc = Jsoup.parse(html, "", org.jsoup.parser.Parser.xmlParser())
        
        // Remove scripts, styles, etc.
        doc.select("script, style, link, meta").remove()
        
        // Add viewport meta for mobile
        doc.head().append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        
        // Add basic styling for EPUB content
        val style = """
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; 
                       line-height: 1.6; margin: 1rem; color: #333; }
                h1, h2, h3, h4, h5, h6 { color: #1a1a1a; margin-top: 1.5em; margin-bottom: 0.5em; }
                p { margin: 0.8em 0; text-align: justify; }
                img { max-width: 100%; height: auto; display: block; margin: 1em auto; }
                .cover { text-align: center; margin: 2em 0; }
                .cover img { max-height: 80vh; }
                pre { overflow-x: auto; background: #f5f5f5; padding: 1em; }
                code { background: #f5f5f5; padding: 0.2em 0.4em; border-radius: 3px; }
                blockquote { border-left: 4px solid #ddd; margin: 1em 0; padding-left: 1em; color: #666; }
                a { color: #0066cc; text-decoration: none; }
                a:hover { text-decoration: underline; }
                @media (prefers-color-scheme: dark) {
                    body { background: #121212; color: #e0e0e0; }
                    h1, h2, h3, h4, h5, h6 { color: #fff; }
                    pre, code { background: #1e1e1e; }
                    blockquote { border-left-color: #555; color: #aaa; }
                }
            </style>
        """.trimIndent()
        
        doc.head().append(style)
        
        return doc.html()
    }
}
