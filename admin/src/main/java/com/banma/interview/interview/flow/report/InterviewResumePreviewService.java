package com.banma.interview.interview.flow.report;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.convention.exception.ServiceException;
import com.banma.interview.interview.dao.entity.InterviewQuestion;
import com.banma.interview.interview.dao.entity.InterviewSession;
import com.banma.interview.interview.service.InterviewQuestionService;
import com.banma.interview.interview.service.InterviewSessionService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 简历预览服务：根据会话找到简历地址，下载文件内容并封装为可直接返回的预览资源。
 */
@Service
@RequiredArgsConstructor
public class InterviewResumePreviewService {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /** 面试题目持久化服务（MySQL） */
    private final InterviewQuestionService interviewQuestionService;
    /** 面试会话持久化服务（Mongo） */
    private final InterviewSessionService interviewSessionService;

    /**
     * 加载简历预览：定位简历地址后下载文件，并推导文件名与 Content-Type。
     *
     * @return 简历文件字节、类型与文件名
     */
    public ResumePreviewResource loadResumePreview(String sessionId) {
        // （1）定位简历地址：题库记录优先，其次会话记录
        InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
        String resumeFileUrl = question == null ? null : question.getResumeFileUrl();
        if (StrUtil.isBlank(resumeFileUrl)) {
            InterviewSession session = interviewSessionService.getBySessionId(sessionId);
            resumeFileUrl = session == null ? null : session.getResumeFileUrl();
        }
        if (StrUtil.isBlank(resumeFileUrl)) {
            throw new ClientException("Resume preview source is missing");
        }

        // （2）发起 GET 下载并校验响应状态与内容
        Request request = new Request.Builder()
                .url(resumeFileUrl.trim())
                .get()
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ServiceException("Failed to download resume preview, HTTP status: " + response.code());
            }
            if (response.body() == null) {
                throw new ServiceException("Failed to download resume preview: empty body");
            }

            byte[] content = response.body().bytes();
            if (content.length == 0) {
                throw new ServiceException("Failed to download resume preview: empty content");
            }

            // （3）推导文件名与 Content-Type 后封装预览资源
            String fileName = resolveFileName(resumeFileUrl, sessionId);
            String contentType = resolveContentType(content, response.header("Content-Type"));
            return new ResumePreviewResource(content, contentType, fileName);
        } catch (ClientException | ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException("Failed to download resume preview: " + ex.getMessage());
        }
    }

    /**
     * 推导下载文件名：优先取 URL 最后一段并补 .pdf，否则用会话 ID 兜底。
     */
    private String resolveFileName(String sourceUrl, String sessionId) {
        HttpUrl parsedUrl = HttpUrl.parse(sourceUrl);
        if (parsedUrl != null && parsedUrl.pathSize() > 0) {
            String lastSegment = parsedUrl.pathSegments().get(parsedUrl.pathSize() - 1);
            if (StrUtil.isNotBlank(lastSegment)) {
                return lastSegment.endsWith(".pdf") ? lastSegment : lastSegment + ".pdf";
            }
        }
        return "resume-" + sessionId + ".pdf";
    }

    /**
     * 确定响应 Content-Type：PDF 文件强制使用 application/pdf，否则沿用响应头。
     */
    private String resolveContentType(byte[] content, String responseContentType) {
        if (isPdf(content)) {
            return "application/pdf";
        }
        return StrUtil.blankToDefault(responseContentType, "application/octet-stream");
    }

    /**
     * 通过文件头魔数判断是否为 PDF。
     */
    private boolean isPdf(byte[] content) {
        return content != null
                && content.length >= 5
                && content[0] == 0x25
                && content[1] == 0x50
                && content[2] == 0x44
                && content[3] == 0x46
                && content[4] == 0x2D;
    }

    /**
     * 简历预览资源：文件字节内容、Content-Type 与文件名。
     */
    @Getter
    public static final class ResumePreviewResource {
        /** 简历文件字节内容 */
        private final byte[] content;
        /** 响应内容类型（MIME） */
        private final String contentType;
        /** 下载文件名 */
        private final String fileName;

        public ResumePreviewResource(byte[] content, String contentType, String fileName) {
            this.content = content;
            this.contentType = contentType;
            this.fileName = fileName;
        }
    }
}
