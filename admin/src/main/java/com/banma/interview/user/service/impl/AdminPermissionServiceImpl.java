package com.banma.interview.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.banma.interview.user.dao.entity.AdminPermission;
import com.banma.interview.user.dao.mapper.AdminPermissionMapper;
import com.banma.interview.user.dao.entity.UserDO;
import com.banma.interview.user.dao.mapper.UserMapper;
import com.banma.interview.common.convention.exception.ServiceException;
import com.banma.interview.user.service.AdminPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理员权限服务实现：查询用户是否为管理员，以及将指定用户授予管理员权限。
 */
@Service
@RequiredArgsConstructor
public class AdminPermissionServiceImpl extends ServiceImpl<AdminPermissionMapper, AdminPermission>
    implements AdminPermissionService {

    private final UserMapper userMapper;

    /**
     * 判断指定用户名是否为管理员，仅统计未删除的权限记录。
     */
    @Override
    public Boolean isAdmin(String username) {
        // （1）按用户名查询未删除的管理员权限记录
        LambdaQueryWrapper<AdminPermission> queryWrapper = Wrappers.lambdaQuery(AdminPermission.class)
                .eq(AdminPermission::getUsername, username)
                .eq(AdminPermission::getDelFlag, 0);
        AdminPermission adminPermission = getOne(queryWrapper);
        // （2）判定权限标记是否为 1
        return adminPermission != null && adminPermission.getIsAdmin() != null && adminPermission.getIsAdmin() == 1;
    }

    /**
     * 将指定用户名设置为管理员：校验用户存在，不存在权限记录则新建，否则更新为管理员。
     */
    @Override
    public void setAdminByUserId(String username) {
        // 使用当前登录用户名查询用户信息验证用户存在
        LambdaQueryWrapper<UserDO> userQueryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username)
                .eq(UserDO::getDelFlag, 0);
        UserDO user = userMapper.selectOne(userQueryWrapper);
        if (user == null) {
            throw new ServiceException("用户不存在");
        }

        // 查询或创建AdminPermission记录
        AdminPermission existingPermission = getOne(Wrappers.lambdaQuery(AdminPermission.class).eq(AdminPermission::getUserId, user.getId()));
        if (existingPermission == null) {
            // 创建新的权限记录
            AdminPermission adminPermission = new AdminPermission();
            adminPermission.setUserId(user.getId());
            adminPermission.setUsername(user.getUsername());
            adminPermission.setIsAdmin(1);
            save(adminPermission);
        } else {
            // 更新现有权限记录
            existingPermission.setIsAdmin(1);
            updateById(existingPermission);
        }
    }
}





