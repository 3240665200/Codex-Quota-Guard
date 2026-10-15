package com.pingche.web.officialsession;

public record OfficialAccountInfo(String userId, String email, String userName, String accountId,
        String accountName, String planType, String accountUserRole, Boolean deactivated, Boolean activeSubscription) { }
