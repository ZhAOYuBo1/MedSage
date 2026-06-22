package com.medsage.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 文档导入服务
 * - 读取知识库 Markdown 文件
 * - 按 H2 标题切分为父块
 * - 每个父块再按段落切分为子块
 * - 存入父子索引表
 *
 * 手动触发：调用 ingestAll() 或通过 API 接口触发
 */
@Service
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    private static final Pattern H1_PATTERN = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern H2_PATTERN = Pattern.compile("^##\\s+(.+)$", Pattern.MULTILINE);
    private static final int CHILD_CHUNK_MAX_CHARS = 100;  // 子块最大字符数

    private final RagDocumentService ragDocumentService;

    @Value("${medsage.rag.knowledge-base-path:知识库}")
    private String knowledgeBasePath;

    public RagIngestionService(RagDocumentService ragDocumentService) {
        this.ragDocumentService = ragDocumentService;
    }

    /**
     * 手动触发：导入全部知识库
     *
     * @return [父块数, 子块数]
     */
    public int[] ingestAll() {
        Path basePath = Paths.get(knowledgeBasePath);
        if (!Files.exists(basePath)) {
            log.warn("知识库目录不存在: {}", basePath.toAbsolutePath());
            return new int[]{0, 0};
        }

        log.info("开始导入 RAG 知识库: {}", basePath.toAbsolutePath());
        List<Path> mdFiles;
        try {
            mdFiles = collectMarkdownFiles(basePath);
        } catch (IOException e) {
            log.error("扫描知识库文件失败", e);
            return new int[]{0, 0};
        }
        log.info("找到 {} 个 Markdown 文件", mdFiles.size());

        int totalParents = 0;
        int totalChildren = 0;

        for (Path mdFile : mdFiles) {
            try {
                String content = Files.readString(mdFile);
                String relativePath = basePath.relativize(mdFile).toString();
                String category = mdFile.getParent().getFileName().toString();

                int[] counts = ingestDocument(relativePath, category, content);
                totalParents += counts[0];
                totalChildren += counts[1];
            } catch (Exception e) {
                log.error("导入文件失败: {}", mdFile, e);
            }
        }

        log.info("RAG 知识库导入完成: {} 个父块, {} 个子块", totalParents, totalChildren);
        return new int[]{totalParents, totalChildren};
    }

    /**
     * 清空并重新导入
     */
    public int[] clearAndReingest() {
        ragDocumentService.clearAll();
        return ingestAll();
    }

    /**
     * 导入单个文档
     *
     * @return [父块数, 子块数]
     */
    public int[] ingestDocument(String sourceFile, String category, String content) {
        // 提取 H1 标题
        String h1Title = extractFirst(H1_PATTERN, content);
        if (h1Title == null) {
            h1Title = sourceFile.replace(".md", "");
        }

        // 按 H2 切分为父块
        List<ParentSection> sections = splitByH2(content, h1Title);

        int parentCount = 0;
        int childCount = 0;
        List<RagDocumentService.ChildChunk> allChildChunks = new ArrayList<>();

        for (ParentSection section : sections) {
            String parentId = UUID.randomUUID().toString();
            String headerPath = section.headerPath();

            // 保存父块
            Map<String, Object> parentMeta = new HashMap<>();
            parentMeta.put("category", category);
            parentMeta.put("h1", h1Title);
            ragDocumentService.saveParentChunk(parentId, sourceFile, headerPath, section.content(), parentMeta);
            parentCount++;

            // 切分子块
            List<String> childTexts = splitIntoChildChunks(section.content());
            for (int i = 0; i < childTexts.size(); i++) {
                String childId = UUID.randomUUID().toString();
                Map<String, Object> childMeta = new HashMap<>();
                childMeta.put("category", category);
                childMeta.put("chunkIndex", i);
                allChildChunks.add(new RagDocumentService.ChildChunk(
                        childId, parentId, childTexts.get(i), sourceFile, headerPath, childMeta
                ));
                childCount++;
            }
        }

        // 批量保存子块
        ragDocumentService.saveChildChunks(allChildChunks);

        log.debug("导入文档: {}, 父块={}, 子块={}", sourceFile, parentCount, childCount);
        return new int[]{parentCount, childCount};
    }

    /**
     * 按 H2 标题切分文档为父块
     */
    private List<ParentSection> splitByH2(String content, String h1Title) {
        List<ParentSection> sections = new ArrayList<>();
        Matcher matcher = H2_PATTERN.matcher(content);

        int lastEnd = 0;
        String lastHeader = null;
        int startPos = 0;

        while (matcher.find()) {
            // 保存上一个 section
            if (lastHeader != null) {
                String sectionContent = content.substring(startPos, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    String headerPath = h1Title + " > " + lastHeader;
                    sections.add(new ParentSection(headerPath, sectionContent));
                }
            } else {
                // H2 之前的内容（intro）
                String intro = content.substring(0, matcher.start()).trim();
                // 去掉 H1 标题行和目录
                intro = cleanIntro(intro, h1Title);
                if (!intro.isEmpty()) {
                    String headerPath = h1Title + " > 概述";
                    sections.add(new ParentSection(headerPath, intro));
                }
            }

            lastHeader = matcher.group(1).trim();
            startPos = matcher.end();
        }

        // 最后一个 section
        if (lastHeader != null) {
            String sectionContent = content.substring(startPos).trim();
            if (!sectionContent.isEmpty()) {
                String headerPath = h1Title + " > " + lastHeader;
                sections.add(new ParentSection(headerPath, sectionContent));
            }
        }

        // 如果没有 H2，整篇作为一个父块
        if (sections.isEmpty()) {
            sections.add(new ParentSection(h1Title, content.trim()));
        }

        return sections;
    }

    /**
     * 将父块内容按段落切分为子块
     * - 按双换行分段
     * - 过长段落按字符数二次切分
     */
    private List<String> splitIntoChildChunks(String content) {
        List<String> chunks = new ArrayList<>();

        // 先按双换行（段落）分割
        String[] paragraphs = content.split("\\n\\n+");

        StringBuilder currentChunk = new StringBuilder();
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) continue;

            // 如果当前段落本身就很长，需要二次切分
            if (paragraph.length() > CHILD_CHUNK_MAX_CHARS) {
                // 先把 currentChunk 存起来
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
                // 长段落按句号切分
                chunks.addAll(splitLongParagraph(paragraph));
                continue;
            }

            // 尝试合并到当前 chunk
            if (currentChunk.length() + paragraph.length() + 2 <= CHILD_CHUNK_MAX_CHARS) {
                if (currentChunk.length() > 0) currentChunk.append("\n\n");
                currentChunk.append(paragraph);
            } else {
                // 当前 chunk 已满，保存并开始新 chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }
                currentChunk = new StringBuilder(paragraph);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 长段落按中文句号、分号等标点切分
     */
    private List<String> splitLongParagraph(String paragraph) {
        List<String> chunks = new ArrayList<>();
        // 按中文句号、分号、问号、感叹号切分
        String[] sentences = paragraph.split("(?<=[。？！；\\n])");

        StringBuilder currentChunk = new StringBuilder();
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;

            if (currentChunk.length() + sentence.length() <= CHILD_CHUNK_MAX_CHARS) {
                currentChunk.append(sentence);
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }
                currentChunk = new StringBuilder(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 清理 H2 之前的内容（去掉目录等）
     */
    private String cleanIntro(String intro, String h1Title) {
        // 去掉 H1 标题行
        intro = H1_PATTERN.matcher(intro).replaceAll("");
        // 去掉目录行（包含 | 的行）
        intro = intro.replaceAll("(?m)^.*\\|.*$", "");
        // 去掉空行和分隔符
        intro = intro.replaceAll("(?m)^[-=]+$", "");
        intro = intro.replaceAll("\\n{3,}", "\n\n");
        return intro.trim();
    }

    private String extractFirst(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private List<Path> collectMarkdownFiles(Path basePath) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".md")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    // ========== 数据结构 ==========

    private record ParentSection(String headerPath, String content) {}
}
