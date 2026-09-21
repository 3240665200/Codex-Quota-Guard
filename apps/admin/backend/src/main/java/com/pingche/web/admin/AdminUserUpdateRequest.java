package com.pingche.web.admin;

import com.pingche.web.user.User;

public record AdminUserUpdateRequest(User.Status status, User.Role role) {
}
