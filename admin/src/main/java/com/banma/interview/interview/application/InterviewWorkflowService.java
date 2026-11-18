package com.banma.interview.interview.application;

import com.banma.interview.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.banma.interview.interview.api.io.req.InterviewAnswerReqDTO;
import com.banma.interview.interview.api.io.req.InterviewQuestionReqDTO;
import com.banma.interview.interview.api.io.resp.InterviewAnswerRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewQuestionRespDTO;
import com.banma.interview.interview.infrastructure.client.dto.QuestionOfficerReqDTO;
import com.banma.interview.interview.infrastructure.client.dto.QuestionOfficerRespDTO;

/**
 * 面试流程服务接口：定义出题、答题、获取题目与仪态评估等流程能力。
 */
public interface InterviewWorkflowService {

    /**
     * 从简历中提取面试题、建议与面试方向。
     *
     * @param requestParam 出题请求参数
     * @return 面试题响应
     */
    InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO requestParam);

    /**
     * 调 Python 出题官并把结果落库（前端 → Java → Python 的 JSON 网关链路）。
     *
     * <p>与 {@link #extractInterviewQuestions} 的区别：后者走旧 multipart 上传链路（已下线），
     * 本方法入参是对 Python 契约的 JSON DTO。</p>
     *
     * @param requestParam 出题请求（sessionId 必填、resumeUrl 可空）
     * @param userName     当前登录用户名，写入题目归档
     * @return Python 出题官的原始响应
     */
    QuestionOfficerRespDTO runQuestionOfficer(QuestionOfficerReqDTO requestParam, String userName);


    /**
     * 提交面试回答并推进流程。
     *
     * @param sessionId 会话 id
     * @param requestParam 回答请求参数
     * @return 回答处理结果
     */
    InterviewAnswerRespDTO answerInterviewQuestion(String sessionId, InterviewAnswerReqDTO requestParam);

    /**
     * 获取下一道面试题。
     *
     * @param sessionId 会话 id
     * @return 下一题响应
     */
    InterviewAnswerRespDTO getNextQuestion(String sessionId);

    /**
     * 获取当前面试题。
     *
     * @param sessionId 会话 id
     * @return 当前题响应
     */
    InterviewAnswerRespDTO getCurrentQuestion(String sessionId);

    /**
     * 对上传的仪态信息进行 AI 评估。
     *
     * @param requestParam 仪态评估请求参数
     * @return 仪态评估结果
     */
    String evaluateDemeanor(DemeanorEvaluationReqDTO requestParam);
}
