package com.banma.interview.user.service.impl;

import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.user.service.MailCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 邮箱验证码服务实现。
 * 职责边界：
 *   本类管验证码生命周期 + 60 秒业务频控（与 5 分钟时效是同一业务规则的两面）
 *   配额由 {@code @RateLimit} 切面管
 *   登录态由 Sa-Token 管
 *
 * Redis key 设计（三类，前缀 mail:）：
 *   mail:code:{scene}:{email}    验证码本体，TTL 5 分钟
 *   mail:limit:{scene}:{email}   60 秒发送频控
 *   mail:fails:{scene}:{email}   校验失败计数（错 5 次作废验证码）
 */
@Slf4j
@Service
@RequiredArgsConstructor
class MailCodeServiceImpl implements MailCodeService {

    private final StringRedisTemplate stringRedisTemplate;
    private final JavaMailSender  mailSender;

    /** 验证码有效期（分钟），默认 5 */
    @Value("${banma.mail.code-ttl-minutes:5}")
    private int codeTtlMinutes;

    /** 同邮箱发送间隔（秒），默认 60 */
    @Value("${banma.mail.send-interval-seconds:60}")
    private int sendIntervalSeconds;

    /** 发件人地址 */
    @Value("${spring.mail.username:}")
    private String mailFrom;

    /** 校验失败上限：达到即作废当前验证码（防 6 位数字被暴力猜解） */
    private static final int MAX_VERIFY_FAILS = 5;

    private static final String KEY_CODE = "mail:code:%s:%s";
    private static final String KEY_LIMIT = "mail:limit:%s:%s";
    private static final String KEY_FAILS = "mail:fails:%s:%s";

    /**
     * 发送验证码。
     * 步骤：
     *   60 秒频控：setIfAbsent 成功才继续（Redis 原子操作，防并发连点）
     *   生成 6 位数字码，写入 Redis（TTL 按配置），清空历史失败计数
     *   发送纯文本邮件；失败时回滚频控 key，让用户不必白等 60 秒
     */
    @Override
    public void sendCode(String scene, String email) {
        //1、频控
        String limitKey = String.format(KEY_LIMIT, scene, email);
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(limitKey, "1", Duration.ofSeconds(sendIntervalSeconds));
        if(Boolean.FALSE.equals(acquired)){
            throw new ClientException("发送太频繁，请 1 分钟后重试");
        }

        //2、生成并存储验证码
        String code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
        stringRedisTemplate.opsForValue().set(
                String.format(KEY_CODE, scene, email),
                code,
                Duration.ofSeconds(codeTtlMinutes)
        );
        //每次发码都重置失败计数
        stringRedisTemplate.delete(String.format(KEY_FAILS, scene, email));

        //3、发邮件
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("【斑马云面试】邮箱验证码");
            message.setText("你的验证码是：" + code + "，" + codeTtlMinutes + " 分钟内有效。\n"
                    + "若非本人操作，请忽略本邮件。");
            mailSender.send(message);
            log.info("验证码已发送: scene={}, email={}", scene, email);
        } catch (MailException e) {
            //邮件发不出去就直接重试
            stringRedisTemplate.delete(limitKey);
            log.error("验证码邮件发送失败: email={}", email, e);
            throw new ClientException("验证码邮件发送失败，请稍后重试");
        }
    }

    /**
     * 校验验证码。
     * 步骤：
     *   取不到码（未发/已过期）→ 直接 false
     *   不匹配 → 失败计数 +1，达到 5 次作废当前码（防暴力猜解）
     *   匹配 → 立即删除（一次性使用）+ 清失败计数 → true
     */
    @Override
    public boolean verify(String scene, String email, String code) {
        String codeKey = String.format(KEY_CODE, scene, email);
        String saved = stringRedisTemplate.opsForValue().get(codeKey);

        //1、无码 = 过期或未发送
        if(saved == null){
            return false;
        }

        //2、不匹配或累计失败次数
        if(!saved.equals(code)){
            String failsKey = String.format(KEY_FAILS, scene, email);
            Long fails = stringRedisTemplate.opsForValue().increment(failsKey);
            if(fails != null && fails == 1){
                stringRedisTemplate.expire(failsKey, Duration.ofMinutes(codeTtlMinutes));
            }
            if(fails != null && fails >= MAX_VERIFY_FAILS){
                stringRedisTemplate.delete(failsKey);
                stringRedisTemplate.delete(codeKey);
                log.warn("验证码失败次数超限已作废: scene={}, email={}", scene, email);
            }
            return false;
        }

        //3、成功则把code和fail删除
        stringRedisTemplate.delete(codeKey);
        stringRedisTemplate.delete(String.format(KEY_FAILS, scene, email));
        return true;
    }
}
