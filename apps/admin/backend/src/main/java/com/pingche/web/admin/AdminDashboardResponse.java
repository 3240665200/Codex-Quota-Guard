package com.pingche.web.admin;

public record AdminDashboardResponse(
        long totalUsers,
        long activeUsers,
        long disabledUsers,
        long adminUsers,
        long newUsersLast7Days
) {
}
