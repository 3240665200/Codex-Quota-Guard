package com.pingche.web.admin;

import com.pingche.web.user.UserResponse;
import java.util.List;

public record AdminUserPageResponse(
        List<UserResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
