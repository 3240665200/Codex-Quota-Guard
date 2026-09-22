package com.pingche.web.codex;

import java.util.List;

public record CodexAccountListResponse(
        List<CodexAccountResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
