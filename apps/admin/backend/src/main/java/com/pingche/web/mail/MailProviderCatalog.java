package com.pingche.web.mail;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MailProviderCatalog {
    public List<Provider> list() {
        return List.of(
                password("QQ", "QQ / Foxmail", "imap.qq.com", "开启 IMAP/SMTP 服务，填写客户端授权码。"),
                password("NETEASE_163", "网易 163", "imap.163.com", "开启 IMAP 服务，填写客户端授权码。"),
                password("NETEASE_126", "网易 126", "imap.126.com", "开启 IMAP 服务，填写客户端授权码。"),
                password("NETEASE_YEAH", "网易 yeah", "imap.yeah.net", "开启 IMAP 服务，填写客户端授权码。"),
                new Provider("GMAIL", "Gmail / Google Workspace", "imap.gmail.com", 993,
                        List.of(MailboxConfig.AuthMode.PASSWORD, MailboxConfig.AuthMode.GOOGLE_OAUTH2),
                        "使用两步验证应用密码，或管理员配置 OAuth Client ID、Client Secret 和已授予 https://mail.google.com/ 权限的 Refresh Token；组织策略可能限制。"),
                new Provider("OUTLOOK", "Outlook / Hotmail / Microsoft 365", "outlook.office365.com", 993,
                        List.of(MailboxConfig.AuthMode.MICROSOFT_OAUTH2),
                        "先开启 IMAP；必须注册 OAuth 应用并授予 IMAP.AccessAsUser.All 和 offline_access，配置 Client ID、Refresh Token，机密应用还需 Client Secret。租户默认 common。"),
                password("ICLOUD", "iCloud", "imap.mail.me.com", "填写 Apple 应用专用密码；用户名可按邮箱官方说明使用地址前缀。"),
                password("YAHOO", "Yahoo", "imap.mail.yahoo.com", "填写 Yahoo 应用密码。"),
                password("ZOHO", "Zoho", "imap.zoho.com", "开启 IMAP，使用应用密码；付费组织为 imappro.zoho.com，地区服务器以账户设置为准，可使用自定义。"),
                password("TENCENT_ENTERPRISE", "腾讯企业邮箱 / 企业微信邮箱", "imap.exmail.qq.com", "开启 IMAP，使用客户端专用密码；海外可使用 hwimap.exmail.qq.com。"),
                password("ALI", "阿里邮箱", "imap.qiye.aliyun.com", "管理员需开放第三方客户端与 IMAP；填写第三方客户端安全密码。"),
                password("FASTMAIL", "Fastmail", "imap.fastmail.com", "填写应用专用密码；Basic 计划不支持 IMAP，需使用支持邮件客户端的套餐。"),
                password("GMX", "GMX", "imap.gmx.com", "先开启 IMAP；填写账号允许的客户端授权凭据，gmx.net 等区域服务器请按官方设置选择自定义。"),
                password("YANDEX", "Yandex", "imap.yandex.com", "启用邮件客户端访问并填写应用密码；区域服务器以邮箱官方说明为准，可使用自定义。"),
                new Provider("CUSTOM", "自定义 IMAPS", "", 993,
                        List.of(MailboxConfig.AuthMode.PASSWORD, MailboxConfig.AuthMode.GOOGLE_OAUTH2, MailboxConfig.AuthMode.MICROSOFT_OAUTH2),
                        "填写服务商公布的公网 IMAPS 地址；始终验证 TLS 证书，不支持回环或内网地址。OAuth 只连接 Google/Microsoft 固定官方端点。"));
    }
    boolean supports(String provider, MailboxConfig.AuthMode mode) {
        return list().stream().anyMatch(p -> p.id().equals(provider) && p.authModes().contains(mode));
    }
    private Provider password(String id, String label, String host, String help) {
        return new Provider(id, label, host, 993, List.of(MailboxConfig.AuthMode.PASSWORD), help);
    }
    public record Provider(String id, String label, String host, int port, List<MailboxConfig.AuthMode> authModes, String help) { }
}
