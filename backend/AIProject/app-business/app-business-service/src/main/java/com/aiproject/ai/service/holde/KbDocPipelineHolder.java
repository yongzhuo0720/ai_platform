package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.bean.KbDocContentBean;
import com.aiproject.ai.service.mapper.KbDocBeanMapper;
import com.aiproject.ai.service.mapper.KbDocContentBeanMapper;
import com.aiproject.ai.service.rag.KbDocRunner;
import com.aiproject.ai.service.rag.KbDocStatus;
import com.aiproject.ai.service.rag.KbDocTextParser;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * 文档上传入口：同步完成「解析 + 校验 + 去重 + 建任务」，耗时的分块/向量化交给 {@link KbDocRunner}。
 *
 * <p>为什么接口只做轻量工作：一次 embedding 可能持续数十秒，放在 HTTP 线程里
 * 会占满 Tomcat 连接、前端还会超时；先落 queued 记录再提交线程池，
 * 前端凭文档列表里的 status/progress 轮询真实进度。</p>
 */
@Slf4j
@Service
public class KbDocPipelineHolder {

    static final String DEFAULT_KB_NAME = "默认知识库";
    /** 同一用户同时处理的文档数上限：防止一个用户把有界队列占满 */
    static final int MAX_ACTIVE_DOCS_PER_USER = 3;
    static final int MAX_NAME_CHARS = 200;
    static final int MAX_KB_NAME_CHARS = 100;
    static final int MAX_RETRY_ATTEMPTS = 5;

    @Autowired
    KbDocBeanMapper<KbDocBean> docMapper;

    @Autowired
    KbDocContentBeanMapper<KbDocContentBean> contentMapper;

    @Autowired
    KbDocRunner runner;

    @Autowired
    @Qualifier("kbDocExecutor")
    TaskExecutor kbDocExecutor;

    /**
     * 上传文档。
     *
     * @param file      服务端解析的文本文件（txt/md/csv/json/log）
     * @param content   浏览器已提取的正文（PDF/DOCX），与 file 二选一
     * @param fileType  浏览器解析时的原始类型
     * @param fileName  浏览器解析时的原始文件名，用于默认文档名
     */
    public Result<KbDocBean> uploadFile(String uid, MultipartFile file, String content,
                                        String name, String kbName, String fileType,
                                        String fileName, Long sizeBytes) {
        if (blank(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        KbDocTextParser.ParsedText parsed;
        String sourceName;
        try {
            if (file != null && !file.isEmpty()) {
                if (file.getSize() > KbDocTextParser.MAX_FILE_BYTES) {
                    return Result.failed(null, "文件过大，单篇最多 20MB");
                }
                sourceName = safeFileName(file.getOriginalFilename());
                parsed = KbDocTextParser.parseFile(sourceName, file.getBytes());
            } else if (!blank(content)) {
                sourceName = safeFileName(fileName);
                parsed = KbDocTextParser.parseClientText(fileType, content, sizeBytes, fileName);
                if (sourceName.isEmpty()) {
                    sourceName = "document." + parsed.getFileType();
                }
            } else {
                return Result.failed(null, "请选择文件或提供文档正文");
            }
        } catch (IllegalArgumentException e) {
            return Result.failed(null, e.getMessage());
        } catch (Exception e) {
            log.warn("[KbDoc] 读取上传文件失败, uid={}: {}", uid, e.getMessage());
            return Result.failed(null, "读取上传文件失败，请稍后重试");
        }

        String displayName = blank(name) ? sourceName : name.trim();
        displayName = sanitizeName(displayName);
        if (displayName.isEmpty()) {
            return Result.failed(null, "文档名称不能为空");
        }
        if (displayName.length() > MAX_NAME_CHARS) {
            return Result.failed(null, "文档名称最多 " + MAX_NAME_CHARS + " 个字符");
        }
        String targetKb = blank(kbName) ? DEFAULT_KB_NAME : kbName.trim();
        if (targetKb.length() > MAX_KB_NAME_CHARS) {
            return Result.failed(null, "知识库名称最多 " + MAX_KB_NAME_CHARS + " 个字符");
        }

        try {
            String hash = KbDocTextParser.sha256(parsed.getContent());
            String duplicate = findDuplicate(uid, targetKb, hash, null);
            if (duplicate != null) {
                return Result.failed(null, "该知识库已存在相同内容的文档「" + duplicate
                        + "」，请勿重复上传");
            }
            String sameName = findSameName(uid, targetKb, displayName);
            if (sameName != null) {
                return Result.failed(null, "同名文档「" + displayName
                        + "」已存在；请先删除旧文档或修改名称后再上传");
            }
            if (countActive(uid) >= MAX_ACTIVE_DOCS_PER_USER) {
                return Result.failed(null, "当前已有 " + MAX_ACTIVE_DOCS_PER_USER
                        + " 篇文档在处理中，请等待完成后再上传");
            }

            String docKey = UUID.randomUUID().toString().replace("-", "");
            long now = System.currentTimeMillis();
            KbDocBean doc = new KbDocBean();
            doc.setDocKey(docKey);
            doc.setKbName(targetKb);
            doc.setName(displayName);
            doc.setCharCount((long) parsed.getContent().length());
            doc.setChunkCount(0);
            doc.setStatus(KbDocStatus.QUEUED);
            doc.setProgress(0);
            doc.setContentHash(hash);
            doc.setFileType(parsed.getFileType());
            doc.setSizeBytes(parsed.getSizeBytes());
            doc.setAttemptCount(1);
            doc.setStartedAt(now);
            doc.setLastuser(uid);
            try {
                if (docMapper.install(doc) <= 0) {
                    return Result.failed(null, "保存文档失败，请稍后重试");
                }
            } catch (Exception e) {
                // 并发上传同一正文时，先查后插都会通过，最终由唯一索引兜底
                if (findDuplicate(uid, targetKb, hash, null) != null) {
                    return Result.failed(null, "该知识库已存在相同内容的文档，请勿重复上传");
                }
                log.error("[KbDoc] 写入文档失败, uid={}: {}", uid, e.getMessage(), e);
                return Result.failed(null, "保存文档失败，请稍后重试");
            }

            Long docIid = findIidByDocKey(uid, docKey);
            if (docIid == null) {
                log.error("[KbDoc] 文档已写入但无法按 docKey 定位, uid={}, docKey={}", uid, docKey);
                deleteByDocKey(uid, docKey);
                return Result.failed(null, "保存文档失败，请稍后重试");
            }
            doc.setIid(docIid);

            KbDocContentBean sourceBean = new KbDocContentBean();
            sourceBean.setDocIid(docIid);
            sourceBean.setKbName(targetKb);
            sourceBean.setContent(parsed.getContent());
            sourceBean.setContentHash(hash);
            sourceBean.setLastuser(uid);
            if (contentMapper.install(sourceBean) <= 0) {
                deleteByDocKey(uid, docKey);
                return Result.failed(null, "保存文档正文失败，请稍后重试");
            }

            try {
                kbDocExecutor.execute(() -> runner.execute(docIid, uid));
            } catch (Exception e) {
                log.error("[KbDoc] 文档任务提交失败, docIid={}: {}", docIid, e.getMessage(), e);
                markQueueRejected(uid, docIid);
                return Result.failed(null, "文档处理队列繁忙，请稍后重试");
            }
            // 内部定位与去重字段不进入响应；返回副本，避免改坏已交给 Mapper 的对象
            return Result.succeed(toPublicView(doc), "文档已受理，正在后台处理");
        } catch (Exception e) {
            log.error("[KbDoc] 上传文档失败, uid={}: {}", uid, e.getMessage(), e);
            return Result.failed(null, "上传失败，请稍后重试");
        }
    }

    /**
     * 失败重试：只接受 failed 文档，且重新排队前再次检查并发上限与内容重复。
     */
    public Result<Boolean> retry(String uid, Long docIid) {
        if (blank(uid)) {
            return Result.failed(false, "未登录或登录已过期");
        }
        if (docIid == null || docIid <= 0) {
            return Result.failed(false, "文档 iid 不能为空");
        }
        try {
            KbDocBean doc = findDoc(uid, docIid);
            if (doc == null) {
                return Result.failed(false, "文档不存在或不属于当前用户");
            }
            if (!KbDocStatus.isFailed(doc.getStatus())) {
                return Result.failed(false, "只有处理失败的文档可以重试");
            }
            int attempts = doc.getAttemptCount() == null ? 0 : doc.getAttemptCount();
            if (attempts >= MAX_RETRY_ATTEMPTS) {
                return Result.failed(false, "该文档已重试 " + attempts + " 次，请删除后重新上传");
            }
            if (countActive(uid) >= MAX_ACTIVE_DOCS_PER_USER) {
                return Result.failed(false, "当前已有 " + MAX_ACTIVE_DOCS_PER_USER
                        + " 篇文档在处理中，请稍后再试");
            }
            KbDocContentBean source = findContent(uid, docIid);
            if (source == null || blank(source.getContent())) {
                return Result.failed(false, "该文档缺少原始正文，无法重试；请删除后重新上传");
            }
            String duplicate = findDuplicate(uid, doc.getKbName(), doc.getContentHash(), docIid);
            if (duplicate != null) {
                return Result.failed(false, "相同内容的文档「" + duplicate
                        + "」已在处理或已就绪，无需重试");
            }

            KbDocBean update = new KbDocBean();
            update.setStatus(KbDocStatus.QUEUED);
            update.setProgress(0);
            update.setAttemptCount(attempts + 1);
            update.setStartedAt(System.currentTimeMillis());
            update.setFinishedAt(0L);
            update.setErrorMsg("");
            KbDocBean where = new KbDocBean();
            where.setIid(docIid);
            where.setLastuser(uid);
            where.setStatus(KbDocStatus.FAILED);
            if (docMapper.update(update, where) <= 0) {
                return Result.failed(false, "文档状态已变化，请刷新后重试");
            }
            try {
                kbDocExecutor.execute(() -> runner.execute(docIid, uid));
            } catch (Exception e) {
                log.error("[KbDoc] 重试任务提交失败, docIid={}: {}", docIid, e.getMessage(), e);
                markQueueRejected(uid, docIid);
                return Result.failed(false, "文档处理队列繁忙，请稍后重试");
            }
            return Result.succeed(true, "文档已重新排队");
        } catch (Exception e) {
            log.error("[KbDoc] 重试文档失败, docIid={}: {}", docIid, e.getMessage(), e);
            return Result.failed(false, "重试失败，请稍后重试");
        }
    }

    /** 同一用户在同一知识库下，活动/已就绪的同内容文档；excludeIid 用于重试时排除自身 */
    private String findDuplicate(String uid, String kbName, String hash, Long excludeIid) {
        if (blank(hash)) {
            return null;
        }
        KbDocBean where = new KbDocBean();
        where.setLastuser(uid);
        where.setKbName(kbName);
        where.setContentHash(hash);
        List<KbDocBean> docs = docMapper.select(null, where);
        if (docs == null) {
            return null;
        }
        for (KbDocBean doc : docs) {
            if (excludeIid != null && excludeIid.equals(doc.getIid())) {
                continue;
            }
            if (KbDocStatus.isActive(doc.getStatus()) || KbDocStatus.isReady(doc.getStatus())) {
                return doc.getName();
            }
        }
        return null;
    }

    private String findSameName(String uid, String kbName, String name) {
        KbDocBean where = new KbDocBean();
        where.setLastuser(uid);
        where.setKbName(kbName);
        where.setName(name);
        List<KbDocBean> docs = docMapper.select(null, where);
        if (docs == null) {
            return null;
        }
        for (KbDocBean doc : docs) {
            if (KbDocStatus.isActive(doc.getStatus()) || KbDocStatus.isReady(doc.getStatus())) {
                return doc.getName();
            }
        }
        return null;
    }

    private int countActive(String uid) {
        KbDocBean where = new KbDocBean();
        where.setLastuser(uid);
        List<KbDocBean> docs = docMapper.select(null, where);
        if (docs == null) {
            return 0;
        }
        int count = 0;
        for (KbDocBean doc : docs) {
            if (KbDocStatus.isActive(doc.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private KbDocBean findDoc(String uid, Long docIid) {
        KbDocBean where = new KbDocBean();
        where.setIid(docIid);
        where.setLastuser(uid);
        List<KbDocBean> docs = docMapper.select(null, where);
        return docs == null || docs.isEmpty() ? null : docs.get(0);
    }

    private KbDocContentBean findContent(String uid, Long docIid) {
        KbDocContentBean where = new KbDocContentBean();
        where.setDocIid(docIid);
        where.setLastuser(uid);
        List<KbDocContentBean> list = contentMapper.select(null, where);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private Long findIidByDocKey(String uid, String docKey) {
        KbDocBean where = new KbDocBean();
        where.setDocKey(docKey);
        where.setLastuser(uid);
        List<KbDocBean> docs = docMapper.select(null, where);
        return docs == null || docs.isEmpty() ? null : docs.get(0).getIid();
    }

    private void deleteByDocKey(String uid, String docKey) {
        KbDocBean where = new KbDocBean();
        where.setDocKey(docKey);
        where.setLastuser(uid);
        try {
            docMapper.delete(where);
        } catch (Exception e) {
            log.error("[KbDoc] 清理未完成文档失败, docKey={}: {}", docKey, e.getMessage());
        }
    }

    private void markQueueRejected(String uid, Long docIid) {
        KbDocBean update = new KbDocBean();
        update.setStatus(KbDocStatus.FAILED);
        update.setErrorMsg("文档处理队列繁忙，请稍后重试");
        update.setFinishedAt(System.currentTimeMillis());
        KbDocBean where = new KbDocBean();
        where.setIid(docIid);
        where.setLastuser(uid);
        try {
            docMapper.update(update, where);
        } catch (Exception e) {
            log.error("[KbDoc] 标记队列拒绝失败, docIid={}: {}", docIid, e.getMessage());
        }
    }

    /** 上传响应只带展示与轮询需要的字段，docKey/contentHash 留在服务端 */
    private static KbDocBean toPublicView(KbDocBean source) {
        KbDocBean view = new KbDocBean();
        view.setIid(source.getIid());
        view.setKbName(source.getKbName());
        view.setName(source.getName());
        view.setCharCount(source.getCharCount());
        view.setChunkCount(source.getChunkCount());
        view.setStatus(source.getStatus());
        view.setFileType(source.getFileType());
        view.setSizeBytes(source.getSizeBytes());
        view.setProgress(source.getProgress());
        view.setErrorMsg(source.getErrorMsg());
        view.setAttemptCount(source.getAttemptCount());
        view.setStartedAt(source.getStartedAt());
        view.setFinishedAt(source.getFinishedAt());
        view.setEmbeddingModel(source.getEmbeddingModel());
        view.setCreatetime(source.getCreatetime());
        return view;
    }

    /** 去掉路径与控制字符；名称只用于展示，不参与任何文件系统操作 */
    private static String safeFileName(String value) {
        if (value == null) {
            return "";
        }
        String name = value.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return sanitizeName(name);
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
