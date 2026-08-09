package com.hify.provider.integration;

import com.hify.provider.adapter.ProviderAdapter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestConfiguration
public class ProviderAdapterFactoryOverride {

    @Bean
    @Primary
    public ProviderAdapter mockProviderAdapter() {
        ProviderAdapter adapter = mock(ProviderAdapter.class);
        when(adapter.supportedTypes()).thenReturn(List.of("OPENAI"));
        return adapter;
    }
}
