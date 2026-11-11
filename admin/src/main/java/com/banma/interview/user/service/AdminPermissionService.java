package com.banma.interview.user.service;

import com.banma.interview.user.dao.entity.AdminPermission;
import com.baomidou.mybatisplus.extension.service.IService;

public interface AdminPermissionService extends IService<AdminPermission> {

    /**
     * 检查用户是否为管理员
     * @param username 用户名
     * @return true：管理员，false：普通用户
     */
    Boolean isAdmin(String username);

    /**
     * 根据用户ID设置用户为管理员
     * @param username 用户ID
     */
    void setAdminByUserId( String username);
}

