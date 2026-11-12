package com.banma.interview.user.api.io.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 邮箱验证码发送请求。
 * scene 用于 Redis key 隔离：register（注册）/ reset_pwd（找回密码）等场景的验证码互不串用。
 */
@Data
public class MailCodeReqDTO {
    /** 收件邮箱） */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 场景标识：register / reset_pwd */
    @NotBlank(message = "场景不能为空")
    private String scene;
}
