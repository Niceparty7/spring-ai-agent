package com.example.agentdemo.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文档解析器：按扩展名把上传文件解析为 {@link Document} 列表。
 *
 * <p>支持 txt / md / pdf 三类，分别使用：
 * <ul>
 *   <li>txt → {@link TextReader}（spring-ai-commons，整文件读为单篇 Document）</li>
 *   <li>md  → {@link MarkdownDocumentReader}（标题层级转 metadata，段落转 Document）</li>
 *   <li>pdf → {@link PagePdfDocumentReader}（按页切分；扫描件无文本层则返回空）</li>
 * </ul>
 *
 * <p>本项目不支持 docx，故刻意不引入 Tika（可显著减少依赖体积）。
 */
@Slf4j
@Component
public class DocumentParser {

    /** 支持的扩展名白名单。 */
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "pdf");

    /**
     * 按扩展名分发解析。
     *
     * @param resource 上传文件资源（{@code MultipartFile#getResource()} 或 FileSystemResource）
     * @param fileName 原始文件名，用于判定扩展名
     * @return 解析出的 Document 列表（尚未附加业务 metadata）
     * @throws IllegalArgumentException 扩展名不受支持
     */
    public List<Document> parse(Resource resource, String fileName) {
        String ext = extOf(fileName);
        log.info("解析文档：{}（类型 {}）", fileName, ext);
        return switch (ext) {
            case "txt" -> new TextReader(resource).get();
            case "md" -> new MarkdownDocumentReader(
                    resource, MarkdownDocumentReaderConfig.defaultConfig()).get();
            case "pdf" -> new PagePdfDocumentReader(resource).get();
            default -> throw new IllegalArgumentException(
                    "不支持的文件类型：" + fileName + "，仅支持 " + SUPPORTED_EXTENSIONS);
        };
    }

    /** 扩展名判定（小写、不含点）。 */
    public String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int i = fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
