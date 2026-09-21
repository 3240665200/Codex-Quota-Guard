package com.codexquotaguard.backend.codex;

import com.codexquotaguard.backend.account.CodexGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CodexGatewayConfiguration {

    @Bean(destroyMethod = "close")
    CodexGateway codexGateway(@Value("${quota-guard.codex-command:codex}") String codexCommand) {
        return new CodexAppServerGateway(codexCommand);
    }
}
