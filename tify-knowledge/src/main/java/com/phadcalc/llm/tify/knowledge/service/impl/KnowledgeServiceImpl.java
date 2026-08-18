package com.phadcalc.llm.tify.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.phadcalc.llm.tify.common.dto.PageResult;
import com.phadcalc.llm.tify.common.dto.Result;
import com.phadcalc.llm.tify.common.exception.BizException;
import com.phadcalc.llm.tify.common.exception.ErrorCode;
import com.phadcalc.llm.tify.knowledge.client.EmbeddingClient;
import com.phadcalc.llm.tify.knowledge.dto.*;
import com.phadcalc.llm.tify.knowledge.entity.Document;
import com.phadcalc.llm.tify.knowledge.entity.KnowledgeBase;
import com.phadcalc.llm.tify.knowledge.mapper.DocumentMapper;
import com.phadcalc.llm.tify.knowledge.mapper.KnowledgeBaseMapper;
import com.phadcalc.llm.tify.knowledge.service.KnowledgeService;
import com.phadcalc.llm.tify.knowledge.vector.ChunkHit;
import com.phadcalc.llm.tify.knowledge.vector.ChunkVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final EmbeddingClient embeddingClient;
    private final ChunkVectorStore chunkVectorStore;
    private final Executor asyncExecutor;

    /** 暂存上传的原始文件字节，processDocument 消费后清除 */
    private static final ConcurrentHashMap<Long, byte[]> DOC_RAW_BYTES = new ConcurrentHashMap<>();
    private static final List<String> ALLOWED_TYPES = Arrays.asList("txt", "md", "pdf");
    private static final long MAX_SIZE = 10 * 1024 * 1024L; // 10MB
    /** 单个分块最大字符数，超出按窗口切分 */
    private static final int MAX_CHUNK_CHARS = 500;
    /** 窗口切分时的重叠字符数，保证上下文衔接 */
    private static final int CHUNK_OVERLAP = 50;

    public KnowledgeServiceImpl(KnowledgeBaseMapper kbMapper,
                                DocumentMapper documentMapper,
                                EmbeddingClient embeddingClient,
                                ChunkVectorStore chunkVectorStore,
                                @Qualifier("asyncExecutor") Executor asyncExecutor) {
        this.kbMapper = kbMapper;
        this.documentMapper = documentMapper;
        this.embeddingClient = embeddingClient;
        this.chunkVectorStore = chunkVectorStore;
        this.asyncExecutor = asyncExecutor;
    }

    // ── 知识库 CRUD ───────────────────────────────────────────

    @Override
    public KnowledgeBaseVO createKb(KnowledgeBaseCreateRequest req) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.getName());
        kb.setDescription(req.getDescription() != null ? req.getDescription() : "");
        kb.setEnabled(1);
        kbMapper.insert(kb);
        return KnowledgeBaseVO.from(kb);
    }

    @Override
    public Result<PageResult<KnowledgeBaseVO>> listKb(int page, int pageSize, String name) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
            .like(name != null && !name.isBlank(), KnowledgeBase::getName, name)
            .orderByDesc(KnowledgeBase::getCreatedAt);
        int size = Math.min(pageSize, 100);
        var p = kbMapper.selectPage(new Page<>(page, size), wrapper);
        List<KnowledgeBaseVO> list = p.getRecords().stream()
            .map(KnowledgeBaseVO::from).collect(Collectors.toList());
        return PageResult.of(list, p.getTotal(), (int) p.getCurrent(), (int) p.getSize());
    }

    @Override
    public KnowledgeBaseVO getKb(Long id) {
        return KnowledgeBaseVO.from(getKbOrThrow(id));
    }

    @Override
    public KnowledgeBaseVO updateKb(Long id, KnowledgeBaseUpdateRequest req) {
        KnowledgeBase kb = getKbOrThrow(id);
        if (req.getName() != null) kb.setName(req.getName());
        if (req.getDescription() != null) kb.setDescription(req.getDescription());
        if (req.getEnabled() != null) kb.setEnabled(req.getEnabled());
        kbMapper.updateById(kb);
        return KnowledgeBaseVO.from(kb);
    }

    @Override
    @Transactional
    public void deleteKb(Long id) {
        getKbOrThrow(id);
        // 逻辑删除下属文档 + 清向量
        List<Document> docs = documentMapper.selectList(
            new LambdaQueryWrapper<Document>().eq(Document::getKnowledgeBaseId, id));
        for (Document doc : docs) {
            chunkVectorStore.deleteByDocument(doc.getId());
            documentMapper.deleteById(doc.getId());
        }
        chunkVectorStore.deleteByKnowledgeBase(id);
        kbMapper.deleteById(id);
    }

    // ── 文档 CRUD ─────────────────────────────────────────────

    @Override
    public DocumentVO uploadDocument(Long kbId, MultipartFile file) {
        getKbOrThrow(kbId);

        // 校验文件类型
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String ext = originalName.contains(".")
            ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
            : "";
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        // 写 document 记录（PENDING），文件字节交给异步管线解析+切分+向量化
        Document doc = new Document();
        doc.setKnowledgeBaseId(kbId);
        doc.setName(originalName);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setStatus("PENDING");
        doc.setErrorMessage("");
        doc.setChunkCount(0);
        documentMapper.insert(doc);

        Long docId = doc.getId();
        try {
            DOC_RAW_BYTES.put(docId, file.getBytes());
        } catch (IOException e) {
            log.warn("读取上传文件失败: {}", e.getMessage());
        }
        asyncExecutor.execute(() -> processDocument(docId));

        return DocumentVO.from(doc);
    }

    @Override
    public Result<PageResult<DocumentVO>> listDocuments(Long kbId, int page, int pageSize) {
        getKbOrThrow(kbId);
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
            .eq(Document::getKnowledgeBaseId, kbId)
            .orderByDesc(Document::getCreatedAt);
        int size = Math.min(pageSize, 100);
        var p = documentMapper.selectPage(new Page<>(page, size), wrapper);
        List<DocumentVO> list = p.getRecords().stream()
            .map(DocumentVO::from).collect(Collectors.toList());
        return PageResult.of(list, p.getTotal(), (int) p.getCurrent(), (int) p.getSize());
    }

    @Override
    public DocumentVO getDocument(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        return DocumentVO.from(doc);
    }

    @Override
    public List<ChunkVO> getChunks(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        return chunkVectorStore.listByDocument(documentId).stream()
                .map(this::toChunkVO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteDocument(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        chunkVectorStore.deleteByDocument(id);
        documentMapper.deleteById(id);
    }

    // ── RAG 检索（真实向量检索）───────────────────────────────

    @Override
    public List<ChunkVO> searchChunks(Long knowledgeBaseId, String query, int topK) {
        try {
            float[] queryEmbedding = embeddingClient.embedQuery(query);
            List<ChunkHit> hits = chunkVectorStore.search(knowledgeBaseId, queryEmbedding, topK);
            log.info("RAG 检索 kbId={} query='{}' 命中 {} 条", knowledgeBaseId, query, hits.size());
            return hits.stream().map(this::toChunkVO).collect(Collectors.toList());
        } catch (Exception e) {
            // 检索失败不能拖垮对话，降级为空
            log.warn("RAG 检索失败 kbId={}，降级为空: {}", knowledgeBaseId, e.getMessage());
            return List.of();
        }
    }

    // ── 文档处理管线（异步）───────────────────────────────────

    private void processDocument(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) return;
        try {
            doc.setStatus("PROCESSING");
            documentMapper.updateById(doc);

            byte[] rawBytes = DOC_RAW_BYTES.remove(documentId);
            if (rawBytes == null || rawBytes.length == 0) {
                throw new IllegalStateException("文件内容为空");
            }

            // step1: 抽取文本（txt/md 直接读 UTF-8，pdf 用 PDFBox）
            String rawText = extractText(rawBytes, doc.getFileType());
            if (rawText == null || rawText.isBlank()) {
                throw new IllegalStateException("未能从文件中解析出文本内容");
            }

            // step2: 文本切块
            List<String> chunks = splitText(rawText);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("文本切分为空");
            }

            // step3: 批量向量化
            List<float[]> vectors = embeddingClient.embed(chunks);

            // step4: 写入向量存储
            for (int i = 0; i < chunks.size(); i++) {
                chunkVectorStore.saveChunk(documentId, doc.getKnowledgeBaseId(), i,
                        chunks.get(i), estimateTokens(chunks.get(i)), vectors.get(i));
            }

            // step5: 更新状态
            doc.setChunkCount(chunks.size());
            doc.setErrorMessage("");
            doc.setStatus("DONE");
            documentMapper.updateById(doc);

            log.info("文档处理完成 docId={} type={} chunks={}", documentId, doc.getFileType(), chunks.size());
        } catch (Exception e) {
            log.error("文档处理失败 docId={}", documentId, e);
            try {
                chunkVectorStore.deleteByDocument(documentId);
            } catch (Exception ex) {
                log.warn("清理失败文档的向量失败: {}", ex.getMessage());
            }
            doc.setStatus("FAILED");
            doc.setErrorMessage(e.getMessage() != null ? e.getMessage() : "处理失败");
            documentMapper.updateById(doc);
        }
    }

    // ── 文本抽取与切分 ────────────────────────────────────────

    private String extractText(byte[] bytes, String fileType) {
        if ("txt".equals(fileType) || "md".equals(fileType)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if ("pdf".equals(fileType)) {
            try (PDDocument pdf = Loader.loadPDF(bytes)) {
                return new PDFTextStripper().getText(pdf);
            } catch (IOException e) {
                throw new IllegalStateException("PDF 解析失败: " + e.getMessage());
            }
        }
        throw new IllegalStateException("不支持的文件类型: " + fileType);
    }

    /**
     * 按段落（连续空行）切分，小段落（如标题、列表项）与后续内容贪心合并成整块，
     * 避免产生信息量过低的孤立分块；单块超过 MAX_CHUNK_CHARS 时按窗口切（带重叠）。
     */
    private List<String> splitText(String text) {
        List<String> result = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n");
        String[] paragraphs = normalized.split("\\n{2,}");

        StringBuilder buf = new StringBuilder();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isBlank()) continue;
            if (buf.isEmpty()) {
                buf.append(trimmed);
            } else if (buf.length() + 1 + trimmed.length() <= MAX_CHUNK_CHARS) {
                buf.append('\n').append(trimmed);
            } else {
                flushChunk(result, buf);
                buf.append(trimmed);
            }
        }
        flushChunk(result, buf);

        // 整篇没有分段且未产生任何分块（如单段超长已被窗口切分，正常不会走到这里）
        if (result.isEmpty() && !normalized.isBlank()) {
            result.addAll(splitByWindow(normalized.trim()));
        }
        return result;
    }

    private void flushChunk(List<String> result, StringBuilder buf) {
        if (buf.isEmpty()) return;
        String chunk = buf.toString();
        buf.setLength(0);
        if (chunk.length() <= MAX_CHUNK_CHARS) {
            result.add(chunk);
        } else {
            result.addAll(splitByWindow(chunk));
        }
    }

    private List<String> splitByWindow(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK_CHARS, text.length());
            // 在窗口末尾附近找最后一个标点/空格断点，避免生切单词
            if (end < text.length()) {
                int cut = text.lastIndexOf('。', end);
                if (cut <= start) cut = text.lastIndexOf(' ', end);
                if (cut > start + MAX_CHUNK_CHARS / 2) end = cut + 1;
            }
            result.add(text.substring(start, end).trim());
            if (end >= text.length()) break;
            start = end - CHUNK_OVERLAP;
        }
        return result.stream().filter(s -> !s.isBlank()).collect(Collectors.toList());
    }

    /** 粗略估算 token 数（中文约 2 字符/token） */
    private int estimateTokens(String content) {
        return content.length() / 2 + 1;
    }

    private ChunkVO toChunkVO(ChunkHit hit) {
        ChunkVO vo = new ChunkVO();
        vo.setId(hit.id());
        vo.setDocumentId(hit.documentId());
        vo.setChunkIndex(hit.chunkIndex());
        vo.setContent(hit.content());
        vo.setTokenCount(hit.tokenCount() != null ? hit.tokenCount() : estimateTokens(hit.content()));
        return vo;
    }

    private KnowledgeBase getKbOrThrow(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) throw new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        return kb;
    }
}
