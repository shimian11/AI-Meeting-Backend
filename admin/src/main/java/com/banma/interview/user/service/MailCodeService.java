package com.banma.interview.user.service;

/**
 * 邮箱验证码服务。
 * 职责：验证码的生成、存储、校验、销毁 + 60 秒业务频控。
 */
public interface MailCodeService {

    /**
     * 发送验证码：60 秒频控 → 生成 6 位数字 → 写 Redis（TTL 5 分钟）→ 发邮件。
     * @param scene 场景标识（register / reset_pwd），用于 Redis key 隔离
     * @param email 收件邮箱，同时作为频控维度
     */
    void sendCode(String scene, String email);

    /**
     * 校验验证码：不匹配累计失败次数（5 次作废），匹配则立即删除（一次性使用）。
     * @return true = 校验通过且验证码已消费
     */
    boolean verify(String scene, String email, String code);
}
