package com.banma.interview.user.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.banma.interview.user.service.MailCodeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.convention.exception.ServiceException;
import com.banma.interview.common.enums.UserErrorCodeEnum;
import com.banma.interview.user.dao.entity.UserDO;
import com.banma.interview.user.dao.mapper.UserMapper;
import com.banma.interview.user.api.io.req.UserLoginReqDTO;
import com.banma.interview.user.api.io.req.UserPageReqDTO;
import com.banma.interview.user.api.io.req.UserRegisterReqDTO;
import com.banma.interview.user.api.io.req.UserUpdateReqDTO;
import com.banma.interview.user.api.io.resp.UserPageRespDTO;
import com.banma.interview.user.api.io.resp.UserRespDTO;
import com.banma.interview.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import static com.banma.interview.common.constant.RedisCacheConstant.LOCK_USER_REGISTER_KEY;
import static com.banma.interview.common.enums.UserErrorCodeEnum.USER_EXIST;
import static com.banma.interview.common.enums.UserErrorCodeEnum.USER_NAME_EXIST;
import static com.banma.interview.common.enums.UserErrorCodeEnum.USER_SAVE_ERROR;

/**
 * User service implementation.
 * 用户服务实现：提供用户查询、注册、更新、登录登出及分页查询能力。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserService {

    private final RBloomFilter<String> userRegisterCachePenetrationBloomFilter;
    private final RedissonClient redissonClient;
    private final MailCodeService mailCodeService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 按用户名查询用户信息，用户不存在时抛出业务异常。
     */
    @Override
    public UserRespDTO getUserByUsername(String username) {
        // （1）按用户名查询用户记录
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        // （2）用户不存在时抛出业务异常
        if (userDO == null) {
            throw new ServiceException(UserErrorCodeEnum.USER_NULL);
        }
        // （3）复制实体属性为响应对象
        UserRespDTO result = new UserRespDTO();
        BeanUtils.copyProperties(userDO, result);
        return result;
    }

    /**
     * 判断用户名是否可用：布隆过滤器未命中表示该用户名尚未注册。
     */
    @Override
    public Boolean hasUsername(String username) {
        return !userRegisterCachePenetrationBloomFilter.contains(username);
    }

    /**
     * 注册新用户：校验用户名、加分布式锁、落库并写入布隆过滤器。
     */
    public void register(UserRegisterReqDTO requestParam) {
        if(!mailCodeService.verify("register", requestParam.getMail(), requestParam.getMailCode())) {
            throw new ClientException("验证码错误或已过期");
        }

        requestParam.setUsername(requestParam.getMail());
        if (!hasUsername(requestParam.getUsername())) {
            throw new ClientException(USER_NAME_EXIST);
        }

        //分布式锁防并发重复注册
        RLock lock = redissonClient.getLock(LOCK_USER_REGISTER_KEY + requestParam.getUsername());
        if(!lock.tryLock()){
            throw new ClientException(USER_NAME_EXIST);
        }
        try {
            UserDO userDO = BeanUtil.toBean(requestParam, UserDO.class);
            userDO.setPassword(passwordEncoder.encode(requestParam.getPassword()));
            int insert = baseMapper.insert(userDO);
            if(insert < 1){
                throw new ClientException(USER_SAVE_ERROR);
            }
            userRegisterCachePenetrationBloomFilter.add(requestParam.getUsername());
        } catch (DuplicateKeyException e) {
            throw new ClientException(USER_EXIST);
        } finally {
            lock.unlock();
        }

    }

    /**
     * 更新当前登录用户的信息，禁止修改他人账号。
     * 本期不做个人中心页面，但接口保留——后续「设置/修改密码」走这里。
     *步骤:
     *   校验请求体与登录态
     *   禁止改他人（username 强制为当前登录者）
     *   密码必须重新 BCrypt 哈希；未传密码则置 null 让 MyBatis-Plus 忽略该字段
     *
     */
    @Override
    public void update(UserUpdateReqDTO requestParam, String currentUsername) {
        // 步骤 1：校验请求体与登录用户非空
        if (requestParam == null) {
            throw new ClientException("request body cannot be empty");
        }
        if (StrUtil.isBlank(currentUsername)) {
            throw new ClientException("current user is not logged in");
        }
        // 步骤 2：禁止修改其他用户的信息
        if (StrUtil.isNotBlank(requestParam.getUsername()) && !currentUsername.equals(requestParam.getUsername())) {
            throw new ClientException("no permission to update other users");
        }

        // 步骤 3：组装更新对象（关键：密码必须哈希后再入库）
        UserDO updateDO = BeanUtil.toBean(requestParam, UserDO.class);
        // username 是身份锚点，不允许改（下游 created_by / 审计日志都挂在它上面）
        updateDO.setUsername(currentUsername);

        if (StrUtil.isNotBlank(requestParam.getPassword())) {
            // 传了密码 → BCrypt 哈希。
            // 若沿用原实现（BeanUtil 直接拷贝），明文会写进库，
            // 之后登录时 passwordEncoder.matches(输入, 明文) 恒为 false —— 用户改完密码就再也登不进去，且不报错
            updateDO.setPassword(passwordEncoder.encode(requestParam.getPassword()));
        } else {
            // 没传密码 → 置 null。MyBatis-Plus 的 update 默认跳过 null 字段，等于"不修改密码"
            updateDO.setPassword(null);
        }

        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUsername, currentUsername);
        baseMapper.update(updateDO, updateWrapper);
    }


    /**
     * 校验密码
     */
    @Override
    public void verifyPassword(UserLoginReqDTO requestParam) {
        //1、按username查
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername())
                .eq(UserDO::getDelFlag, 0);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        //2、BCrypt对比
        if(userDO == null || !passwordEncoder.matches(requestParam.getPassword(), userDO.getPassword())){
            throw new ClientException("账号或密码错误");
        }
    }

    /**
     * 分页查询用户列表，支持关键字模糊匹配与创建时间排序。
     */
    @Override
    public IPage<UserPageRespDTO> pageUsers(UserPageReqDTO requestParam) {
        // （1）构造分页参数与基础查询条件
        Page<UserDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());

        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getDelFlag, 0);

        // （2）关键字非空时按用户名、姓名、邮箱模糊匹配
        if (requestParam.getKeyword() != null && !requestParam.getKeyword().trim().isEmpty()) {
            String keyword = requestParam.getKeyword().trim();
            queryWrapper.and(wrapper -> wrapper
                    .like(UserDO::getUsername, keyword)
                    .or().like(UserDO::getRealName, keyword)
                    .or().like(UserDO::getMail, keyword)
            );
        }

        // （3）按创建时间排序，默认倒序
        if (requestParam.getCreateTimeSort() != null) {
            if ("asc".equalsIgnoreCase(requestParam.getCreateTimeSort())) {
                queryWrapper.orderByAsc(UserDO::getCreateTime);
            } else {
                queryWrapper.orderByDesc(UserDO::getCreateTime);
            }
        } else {
            queryWrapper.orderByDesc(UserDO::getCreateTime);
        }

        // （4）执行分页查询
        IPage<UserDO> userPage = baseMapper.selectPage(page, queryWrapper);

        // （5）组装分页响应并将实体转换为 DTO
        Page<UserPageRespDTO> resultPage = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        resultPage.setTotal(userPage.getTotal());

        if (CollUtil.isNotEmpty(userPage.getRecords())) {
            resultPage.setRecords(userPage.getRecords().stream()
                    .map(userDO -> {
                        UserPageRespDTO respDTO = new UserPageRespDTO();
                        BeanUtils.copyProperties(userDO, respDTO);
                        return respDTO;
                    })
                    .toList());
        }

        return resultPage;
    }
}
