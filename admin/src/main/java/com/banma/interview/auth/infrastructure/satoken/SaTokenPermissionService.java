package com.banma.interview.auth.infrastructure.satoken;

import com.banma.interview.auth.application.PermissionService;
import com.banma.interview.user.service.AdminPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SaTokenPermissionService implements PermissionService {

    private final AdminPermissionService adminPermissionService;

    @Override
    public boolean isAdmin(String username) {
        return adminPermissionService.isAdmin(username);
    }
}
