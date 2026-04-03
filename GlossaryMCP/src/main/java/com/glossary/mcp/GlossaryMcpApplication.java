package com.glossary.mcp;

import com.glossary.mcp.service.GlossaryToolService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GlossaryMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlossaryMcpApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider glossaryTools(GlossaryToolService glossaryToolService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(glossaryToolService)
                .build();
    }
}
