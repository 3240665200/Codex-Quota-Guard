package com.codexquotaguard.backend.account;

import com.codexquotaguard.backend.codex.DeviceLogin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final CodexAccountService accountService;

    public AccountController(CodexAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/overview")
    public AccountOverview overview() {
        return accountService.readOverview();
    }

    @PostMapping("/login/device")
    public DeviceLogin startDeviceLogin() {
        return accountService.startDeviceLogin();
    }

    @PostMapping("/logout")
    public void logout() {
        accountService.logout();
    }
}
