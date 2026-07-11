package com.epubreader

import android.content.Context
import android.net.Uri
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node
import org.w3c.dom.NodeList

class EpubParser(private val context: Context) {

    companion object {
        private const val TAG = "EpubParser"
        private const val EPUB_MIME = "application/epub+zip"
        private const val NCX_MIME = "application/x-dtbncx+xml"
        private const val NAV_MIME = "application/xhtml+xml"
        private const val XHTML_MIME = "application/xhtml+xml"
        private const val HTML_MIME = "text/html"
    }

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

    @Throws(Exception::class)
    fun parseFromFile(file: File): EpubBook {
        Log.d(TAG, "Parsing EPUB: ${file.absolutePath}")

        val zipFile = ZipFile(file)
        try {
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

            val metadata = opfDoc.select("metadata").first()
            val title = metadata?.select("dc\\:title, title").first()?.text() ?: file.nameWithoutExtension
            val author = metadata?.select("dc\\:creator, creator").first()?.text() ?: "Unknown Author"
            val language = metadata?.select("dc\\:language, language").first()?.text() ?: "en"
            val identifier = metadata?.select("dc\\:identifier, identifier").first()?.text() ?: file.name
            val publisher = metadata?.select("dc\\:publisher, publisher").first()?.text() ?: ""
            val description = metadata?.select("dc\\:description, description").first()?.text() ?: ""

            val manifestItems = mutableMapOf<String, ManifestItem>()
            opfDoc.select("manifest item").forEach { item ->
                val id = item.attr("id")
                val href = item.attr("href")
                val mediaType = item.attr("media-type")
                val properties = item.attr("properties")
                manifestItems[id] = ManifestItem(id, href, mediaType, properties)
            }

            val spineItems = mutableListOf<String>()
            opfDoc.select("spine itemref").forEach { itemref ->
                val idref = itemref.attr("idref")
                spineItems.add(idref)
            }

            val coverImagePath = parseCoverImage(opfDoc, manifestItems, zipFile, opfPath)

            val toc = parseToc(opfDoc, manifestItems, zipFile, opfPath)

            val basePath = opfPath.substringBeforeLast('/') + "/"
            val chapters = parseChapters(spineItems, manifestItems, zipFile, basePath, toc)

            return EpubBook(
                id = identifier,
                title = title,
                author = author,
                identifier = identifier,
                language = language,
                publisher = publisher,
                description = description,
                coverHref = coverImagePath ?: "",
                coverImage = coverImagePath?.let { File(it).readBytes() },
                chapters = chapters,
                spine = spineItems,
                filePath = file.absolutePath
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
        val coverMeta = opfDoc.select("meta[name=cover]").firstOrNull()
        val coverId = coverMeta?.attr("content")
        
        if (coverId != null && coverId.isNotEmpty()) {
            manifestItems[coverId]?.let { item ->
                return extractFile(zipFile, opfPath, item.href)
            }
        }
        
        manifestItems.values.firstOrNull { it.properties.contains("cover-image") }?.let { item ->
            return extractFile(zipFile, opfPath, item.href)
        }
        
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
        
        val tocMap = mutableMapOf<String, TocItem>()
        fun flattenToc(items: List<TocItem>) {
            items.forEach { item ->
                tocMap[item.href] = item
                if (item.children.isNotEmpty()) flattenToc(item.children)
            }
        }
        flattenToc(toc)
        
        spineItems.forEach { idref ->
            manifestItems[idref]?.let { item ->
                if (item.mediaType == XHTML_MIME || item.mediaType == HTML_MIME) {
                    val fullPath = basePath + item.href
                    val entry = zipFile.getEntry(fullPath)
                    if (entry != null) {
                        val content = zipFile.getInputStream(entry).readBytes().decodeToString()
                        val tocItem = tocMap[item.href]
                        val title = tocItem?.title ?: extractTitleFromContent(content) ?: "Chapter ${order + 1}"
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

    private fun extractTitleFromContent(content: String): String? {
        val doc = Jsoup.parse(content, "", org.jsoup.parser.Parser.xmlParser())
        return doc.select("title").first()?.text()
            ?: doc.select("h1, h2, h3").first()?.text()
    }

    private fun cleanHtmlContent(html: String): String {
        if (html.isBlank()) return ""
        
        val doc = Jsoup.parse(html, "", org.jsoup.parser.Parser.xmlParser())
        
        doc.select("script, style, link[rel=stylesheet], meta, noselect").remove()
        
        doc.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.isNotEmpty() && !src.startsWith("data:") && !src.startsWith("http")) {
                img.attr("data-original-src", src)
            }
        }
        
        val head = doc.head()
        if (head != null) {
            head.append("""
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0">
                <style>
                    @font-face {
                        font-family: 'Noto Sans SC';
                        src: local('Noto Sans SC'), local('NotoSansSC-Regular');
                    }
                    @font-face {
                        font-family: 'Noto Sans TC';
                        src: local('Noto Sans TC'), local('NotoSansTC-Regular');
                    }
                    @font-face {
                        font-family: 'Noto Serif SC';
                        src: local('Noto Serif SC'), local('NotoSerifSC-Regular');
                    }
                    @font-face {
                        font-family: 'Noto Serif TC';
                        src: local('Noto Serif TC'), local('NotoSerifTC-Regular');
                    }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 
                                     'Noto Sans SC', 'Noto Sans TC', 'PingFang SC', 'Microsoft YaHei', sans-serif;
                        line-height: 1.7;
                        margin: 1rem;
                        color: #1a1a1a;
                        word-wrap: break-word;
                        overflow-wrap: break-word;
                        text-align: justify;
                        -webkit-text-size-adjust: 100%;
                    }
                    h1, h2, h3, h4, h5, h6 {
                        color: #1a1a1a;
                        margin-top: 1.5em;
                        margin-bottom: 0.5em;
                        line-height: 1.3;
                        font-weight: 600;
                    }
                    p { margin: 0.8em 0; }
                    img { max-width: 100%; height: auto; display: block; margin: 1em auto; }
                    .cover { text-align: center; margin: 2em 0; }
                    .cover img { max-height: 80vh; }
                    pre { overflow-x: auto; background: #f5f5f5; padding: 1em; border-radius: 4px; }
                    code { background: #f5f5f5; padding: 0.2em 0.4em; border-radius: 3px; }
                    blockquote { border-left: 4px solid #ccc; margin: 1em 0; padding-left: 1em; color: #666; }
                    hr { border: none; border-top: 1px solid #eee; margin: 2em 0; }
                    a { color: #0066cc; text-decoration: none; }
                    a:hover { text-decoration: underline; }
                    @media (prefers-color-scheme: dark) {
                        body { color: #e0e0e0; background: #121212; }
                        h1, h2, h3, h4, h5, h6 { color: #fff; }
                        code { background: #333; color: #f8f8f2; }
                        pre { background: #1e1e1e; }
                        blockquote { border-left-color: #555; color: #aaa; }
                        hr { border-top-color: #333; }
                    }
                    body.sepia { 
                        background: #f4e4bc; 
                        color: #5d4e37; 
                    }
                    body.sepia h1, body.sepia h2, body.sepia h3, 
                    body.sepia h4, body.sepia h5, body.sepia h6 { color: #5d4e37; }
                    body.sepia code { background: #e8d8a8; }
                    body.sepia pre { background: #e8d8a8; }
                    body.sepia blockquote { border-left-color: #c4b596; color: #8a7a5a; }
                    body.sepia hr { border-top-color: #d4c4a4; }
                    :lang(zh), :lang(zh-CN), :lang(zh-TW), :lang(zh-Hans), :lang(zh-Hant) {
                        font-family: 'Noto Sans SC', 'Noto Sans TC', 'PingFang SC', 'Microsoft YaHei', sans-serif;
                        line-height: 1.8;
                    }
                    :lang(zh) p { text-align: justify; text-justify: inter-ideograph; }
                    .vertical-text {
                        writing-mode: vertical-rl;
                        text-orientation: mixed;
                        height: 100vh;
                        overflow-y: auto;
                    }
                </style>
            """.trimIndent())
        }
        
        return doc.html()
    }

    fun extractImages(zipFile: ZipFile, basePath: String, manifestItems: Map<String, ManifestItem>, baseDir: File): Map<String, File> {
        val images = mutableMapOf<String, File>()
        manifestItems.values.filter { it.mediaType.startsWith("image/") }.forEach { resource ->
            val fullPath = basePath + resource.href
            val entry = zipFile.getEntry(fullPath)
            if (entry != null) {
                val fileName = resource.href.substringAfterLast('/')
                val file = File(baseDir, fileName)
                FileOutputStream(file).use { output ->
                    zipFile.getInputStream(entry).copyTo(output)
                }
                images[resource.href] = file
            }
        }
        return images
    }
}

data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String = ""
)

data class TocItem(
    val title: String,
    val href: String,
    val children: List<TocItem> = emptyList(),
    val level: Int = 0
)
