package com.banma.interview.common.enums;

import com.banma.interview.common.convention.errorcode.IErrorCode;

/**
 * 文件与对象存储错误码（B0005xx）。
 */
public enum FileErrorCodeEnum implements IErrorCode {

    /** 上传文件为空 */
    FILE_EMPTY("B000501", "上传文件为空"),

    /** 超过业务上限（简历 20MB） */
    FILE_TOO_LARGE("B000502", "文件超过大小限制"),

    /** 扩展名不在白名单（一期仅 PDF） */
    FILE_TYPE_UNSUPPORTED("B000503", "文件类型不支持，仅支持 PDF"),

    /** 文件头与扩展名不符（伪造类型） */
    FILE_CONTENT_INVALID("B000504", "文件内容不是有效的 PDF"),

    /** 读取上传流失败 */
    FILE_READ_FAILED("B000505", "读取上传文件失败"),

    /** 对象存储写入失败 */
    FILE_UPLOAD_FAILED("B000506", "文件上传失败"),

    /** 文件资产记录落库失败 */
    FILE_RECORD_SAVE_FAILED("B000507", "文件记录保存失败"),

    /** 预签名 URL 生成失败 */
    FILE_PRESIGN_FAILED("B000508", "生成文件访问地址失败"),

    /** 对象删除失败 */
    FILE_DELETE_FAILED("B000509", "文件删除失败");

    private final String code;

    private final String message;

    FileErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
